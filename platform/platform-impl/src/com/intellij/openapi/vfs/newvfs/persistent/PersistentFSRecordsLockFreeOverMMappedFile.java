// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.newvfs.persistent;

import com.intellij.util.io.CorruptedException;
import com.intellij.util.io.IOUtil;
import com.intellij.util.io.Unmappable;
import com.intellij.util.io.dev.mmapped.MMappedFileStorage;
import com.intellij.util.io.dev.mmapped.MMappedFileStorage.Page;
import com.intellij.serviceContainer.AlreadyDisposedException;
import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.intellij.openapi.vfs.newvfs.persistent.PersistentFSHeaders.*;
import static com.intellij.util.SystemProperties.getIntProperty;
import static java.lang.invoke.MethodHandles.byteBufferViewVarHandle;
import static java.nio.ByteOrder.nativeOrder;

/**
 * Implementation uses memory-mapped file (real one, not our emulation of it via {@link com.intellij.util.io.FilePageCache}).
 */
@ApiStatus.Internal
public final class PersistentFSRecordsLockFreeOverMMappedFile implements PersistentFSRecordsStorage,
                                                                         IPersistentFSRecordsStorage,
                                                                         Unmappable {

  /**
   * How many un-allocated records (i.e. after {@link #maxAllocatedID()}) to check to be empty (all-zero).
   * Everything in the file after {@link #maxAllocatedID()} should be 0 -- but EA-984945 shows sometimes it
   * is not 0, so this self-check was introduced: scan first N records in yet-un-allocated region, and check
   * all the bytes are 0.
   * Set value to 0 to disable the check altogether.
   */
  private static final int UNALLOCATED_RECORDS_TO_CHECK_ZEROED = getIntProperty("vfs.check-unallocated-records-zeroed", 4);

  /* ================ FILE HEADER FIELDS LAYOUT ======================================================= */
  /**
   * For mmapped implementation file size is page-aligned, we can't calculate records size from it.
   * Instead, we store allocated records count in header, in a reserved field (HEADER_RESERVED_OFFSET_1)
   */
  private static final int HEADER_RECORDS_ALLOCATED = HEADER_RESERVED_OFFSET_1;

  @VisibleForTesting
  static final int HEADER_SIZE = PersistentFSHeaders.HEADER_SIZE;

  @VisibleForTesting
  static final class RecordLayout {
    //@formatter:off
    static final int PARENT_REF_OFFSET        = 0;   //int32
    static final int NAME_REF_OFFSET          = 4;   //int32
    static final int FLAGS_OFFSET             = 8;   //int32
    static final int ATTR_REF_OFFSET          = 12;  //int32
    static final int CONTENT_REF_OFFSET       = 16;  //int32
    static final int MOD_COUNT_OFFSET         = 20;  //int32

    //RC: moved TIMESTAMP 1 field down so both LONG fields are 8-byte aligned (for atomic accesses alignment is important)
    static final int TIMESTAMP_OFFSET         = 24;  //int64
    static final int LENGTH_OFFSET            = 32;  //int64

    static final int RECORD_SIZE_IN_BYTES     = 40;
    //@formatter:on
  }

  public static final int DEFAULT_MAPPED_CHUNK_SIZE = getIntProperty("vfs.records-storage.memory-mapped.mapped-chunk-size", 1 << 26);//64Mb

  private static final VarHandle INT_HANDLE = byteBufferViewVarHandle(int[].class, nativeOrder()).withInvokeExactBehavior();
  private static final VarHandle LONG_HANDLE = byteBufferViewVarHandle(long[].class, nativeOrder()).withInvokeExactBehavior();


  private final @NotNull MMappedFileStorage storage;
  /** Cached page(0) for faster access */
  private transient Page headerPage;


  /**
   * Incremented on each update of anything in the storage -- header, record. Hence be seen as 'version'
   * of storage content -- not storage format version, but current storage content.
   * Stored in {@link PersistentFSHeaders#HEADER_GLOBAL_MOD_COUNT_OFFSET} header field.
   * If a record is updated -> current value of globalModCount is 'stamped' into a record MOD_COUNT field.
   */
  private final AtomicInteger globalModCount = new AtomicInteger(0);
  //MAYBE RC: if we increment .globalModCount on _each_ modification -- this rises interesting possibility to
  //          detect corruptions without corruption marker: currently stored globalModCount (HEADER_GLOBAL_MOD_COUNT_OFFSET)
  //          is a version of last record what is guaranteed to be correctly written. So we could scan all
  //          records on file open, and ensure no one of them has .modCount > .globalModCount read from header.
  //          If this is true -- most likely records were stored correctly, even if app was crushed. If not, if
  //          we find a record(s) with modCount>globalModCount => there were writes unfinished on app crush, and
  //          likely at least those records are corrupted.

  //MAYBE RC: instead of dirty flag -> just compare .globalModCount != getIntHeaderField(HEADER_GLOBAL_MOD_COUNT_OFFSET)
  private final AtomicBoolean dirty = new AtomicBoolean(false);

  //cached for faster access:
  private final transient int pageSize;
  private final transient int recordsPerPage;
  private final transient int recordsOnHeaderPage;

  private final transient HeaderAccessor headerAccessor = new HeaderAccessor(this);

  /**
   * Cached value {@link #maxAllocatedID()}.
   * fileId check against {@link #maxAllocatedID()} is very frequent, so it is worth to optimize it.
   * <p>
   * {@link #maxAllocatedID()} is always increasing, so we can cache last returned value, check against
   * it first, and only if fileId > lastMaxAllocatedId -- re-check against actual {@link #maxAllocatedID()}
   * value. This way most of checks should terminate early, on first check against simple field.
   * <p>
   * Thread-safety: field don't need to be volatile, since we have an invariant "if an id was valid at some
   * point, it is always valid since then" -- which means that if [id <= lastMaxAllocatedId] => id is valid,
   * regardless of how much outdated lastMaxAllocatedId value is. And if [id > lastMaxAllocatedId] => we'll
   * re-check against actual value anyway.
   */
  private int cachedMaxAllocatedId;

  public PersistentFSRecordsLockFreeOverMMappedFile(@NotNull MMappedFileStorage storage) throws IOException {
    final int pageSize = storage.pageSize();
    if (pageSize < HEADER_SIZE) {
      throw new IllegalArgumentException("pageSize(=" + pageSize + ") must fit header(=" + HEADER_SIZE + " b)");
    }
    this.storage = storage;

    this.pageSize = pageSize;
    recordsPerPage = pageSize / RecordLayout.RECORD_SIZE_IN_BYTES;
    recordsOnHeaderPage = (pageSize - HEADER_SIZE) / RecordLayout.RECORD_SIZE_IN_BYTES;

    headerPage = this.storage.pageByOffset(0);

    final int modCount = getIntHeaderField(HEADER_GLOBAL_MOD_COUNT_OFFSET);
    globalModCount.set(modCount);

    if (UNALLOCATED_RECORDS_TO_CHECK_ZEROED > 0) {
      //MAYBE RC: make method public, and instead of ctor -- call it explicitly in NotClosedProperlyRecoverer, or
      //          even during quick self-check?
      checkUnAllocatedRegionIsZeroed(UNALLOCATED_RECORDS_TO_CHECK_ZEROED);
    }

    cachedMaxAllocatedId = maxAllocatedID();
  }

  @Override
  public <R, E extends Throwable> R readRecord(final int recordId,
                                               final @NotNull RecordReader<R, E> reader) throws E, IOException {
    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final Page page = storage.pageByOffset(recordOffsetInFile);
    final RecordAccessor recordAccessor = new RecordAccessor(recordId, recordOffsetOnPage, page, this);
    return reader.readRecord(recordAccessor);
  }

  @Override
  public <E extends Throwable> int updateRecord(final int recordId,
                                                final @NotNull RecordUpdater<E> updater) throws E, IOException {
    final int trueRecordId = (recordId <= NULL_ID) ?
                             allocateRecord() :
                             recordId;
    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final Page page = storage.pageByOffset(recordOffsetInFile);
    //RC: hope EscapeAnalysis removes the allocation here:
    final RecordAccessor recordAccessor = new RecordAccessor(recordId, recordOffsetOnPage, page, this);
    boolean updated = updater.updateRecord(recordAccessor);
    if (updated) {
      incrementRecordVersion(recordAccessor.pageBuffer, recordOffsetOnPage);
    }
    return trueRecordId;
  }

  @Override
  public <R, E extends Throwable> R readHeader(final @NotNull HeaderReader<R, E> reader) throws E, IOException {
    return reader.readHeader(headerAccessor);
  }

  @Override
  public <E extends Throwable> void updateHeader(final @NotNull HeaderUpdater<E> updater) throws E, IOException {
    if (updater.updateHeader(headerAccessor)) {
      globalModCount.incrementAndGet();
      dirty.compareAndSet(true, false);
    }
  }


  private static final class RecordAccessor implements RecordForUpdate {
    private final int recordId;
    private final int recordOffsetInPage;
    private final transient ByteBuffer pageBuffer;
    private final @NotNull PersistentFSRecordsLockFreeOverMMappedFile records;

    private RecordAccessor(final int recordId,
                           final int recordOffsetInPage,
                           final Page recordPage,
                           final @NotNull PersistentFSRecordsLockFreeOverMMappedFile records) {
      this.recordId = recordId;
      this.recordOffsetInPage = recordOffsetInPage;
      pageBuffer = recordPage.rawPageBuffer();
      this.records = records;
    }

    @Override
    public int recordId() {
      return recordId;
    }

    @Override
    public int getAttributeRecordId() {
      return getIntField(RecordLayout.ATTR_REF_OFFSET);
    }

    @Override
    public int getParent() {
      return getIntField(RecordLayout.PARENT_REF_OFFSET);
    }

    @Override
    public int getNameId() {
      return getIntField(RecordLayout.NAME_REF_OFFSET);
    }

    @Override
    public long getLength() {
      return getLongField(RecordLayout.LENGTH_OFFSET);
    }

    @Override
    public long getTimestamp() {
      return getLongField(RecordLayout.TIMESTAMP_OFFSET);
    }

    @Override
    public int getModCount() {
      return getIntField(RecordLayout.MOD_COUNT_OFFSET);
    }

    @Override
    public int getContentRecordId() {
      return getIntField(RecordLayout.CONTENT_REF_OFFSET);
    }

    @Override
    public @PersistentFS.Attributes int getFlags() {
      //noinspection MagicConstant
      return getIntField(RecordLayout.FLAGS_OFFSET);
    }

    @Override
    public void setAttributeRecordId(final int attributeRecordId) {
      checkValidIdField(recordId, attributeRecordId, "attributeRecordId");
      setIntField(RecordLayout.ATTR_REF_OFFSET, attributeRecordId);
    }

    @Override
    public void setParent(final int parentId) {
      records.checkParentIdIsValid(parentId);
      setIntField(RecordLayout.PARENT_REF_OFFSET, parentId);
    }

    @Override
    public void setNameId(final int nameId) {
      checkValidIdField(recordId, nameId, "nameId");
      setIntField(RecordLayout.NAME_REF_OFFSET, nameId);
    }

    @Override
    public boolean setFlags(final @PersistentFS.Attributes int flags) {
      return setIntFieldIfChanged(RecordLayout.FLAGS_OFFSET, flags);
    }

    @Override
    public boolean setLength(final long length) {
      return setLongFieldIfChanged(RecordLayout.LENGTH_OFFSET, length);
    }

    @Override
    public boolean setTimestamp(final long timestamp) {
      return setLongFieldIfChanged(RecordLayout.TIMESTAMP_OFFSET, timestamp);
    }

    @Override
    public boolean setContentRecordId(final int contentRecordId) {
      checkValidIdField(recordId, contentRecordId, "contentRecordId");
      return setIntFieldIfChanged(RecordLayout.CONTENT_REF_OFFSET, contentRecordId);
    }


    private long getLongField(final int fieldRelativeOffset) {
      return (long)LONG_HANDLE.getVolatile(pageBuffer, recordOffsetInPage + fieldRelativeOffset);
    }

    private boolean setLongFieldIfChanged(final int fieldRelativeOffset,
                                          final long newValue) {
      final int fieldOffsetInPage = recordOffsetInPage + fieldRelativeOffset;
      final long oldValue = (long)LONG_HANDLE.getVolatile(pageBuffer, fieldOffsetInPage);
      if (oldValue != newValue) {
        setLongVolatile(pageBuffer, fieldOffsetInPage, newValue);
        return true;
      }
      return false;
    }

    private int getIntField(final int fieldRelativeOffset) {
      return (int)INT_HANDLE.getVolatile(pageBuffer, recordOffsetInPage + fieldRelativeOffset);
    }

    private void setIntField(final int fieldRelativeOffset,
                             final int newValue) {
      setIntVolatile(pageBuffer, recordOffsetInPage + fieldRelativeOffset, newValue);
    }

    private boolean setIntFieldIfChanged(final int fieldRelativeOffset,
                                         final int newValue) {
      final int fieldOffsetInPage = recordOffsetInPage + fieldRelativeOffset;
      final int oldValue = (int)INT_HANDLE.getVolatile(pageBuffer, fieldOffsetInPage);
      if (oldValue != newValue) {
        setIntVolatile(pageBuffer, fieldOffsetInPage, newValue);
        return true;
      }
      return false;
    }
  }

  private static final class HeaderAccessor implements HeaderForUpdate {
    private final @NotNull PersistentFSRecordsLockFreeOverMMappedFile records;

    private HeaderAccessor(final @NotNull PersistentFSRecordsLockFreeOverMMappedFile records) { this.records = records; }

    @Override
    public long getTimestamp() throws IOException {
      return records.getTimestamp();
    }

    @Override
    public int getConnectionStatus() throws IOException {
      return records.getConnectionStatus();
    }

    @Override
    public int getVersion() throws IOException {
      return records.getVersion();
    }

    @Override
    public int getGlobalModCount() {
      return records.getGlobalModCount();
    }

    @Override
    public void setConnectionStatus(final int code) throws IOException {
      records.setConnectionStatus(code);
    }

    @Override
    public void setVersion(final int version) throws IOException {
      records.setVersion(version);
    }
  }


  // ==== records operations:  ================================================================ //


  @Override
  public int allocateRecord() {
    dirty.compareAndSet(false, true);
    final Page headerPage = headerPage();
    final ByteBuffer headerPageBuffer = headerPage.rawPageBuffer();
    while (true) {// CAS loop:
      int allocatedRecords = (int)INT_HANDLE.getVolatile(headerPageBuffer, HEADER_RECORDS_ALLOCATED);
      int newAllocatedRecords = allocatedRecords + 1;
      if (INT_HANDLE.compareAndSet(headerPageBuffer, HEADER_RECORDS_ALLOCATED, allocatedRecords, newAllocatedRecords)) {
        return newAllocatedRecords;
      }
    }
  }

  // 'one field at a time' operations

  @Override
  public void setAttributeRecordId(final int recordId,
                                   final int attributeRecordId) throws IOException {
    checkValidIdField(recordId, attributeRecordId, "attributeRecordId");
    setIntField(recordId, RecordLayout.ATTR_REF_OFFSET, attributeRecordId);
  }

  @Override
  public int getAttributeRecordId(final int recordId) throws IOException {
    return getIntField(recordId, RecordLayout.ATTR_REF_OFFSET);
  }

  @Override
  public int getParent(final int recordId) throws IOException {
    return getIntField(recordId, RecordLayout.PARENT_REF_OFFSET);
  }

  @Override
  public void setParent(final int recordId,
                        final int parentId) throws IOException {
    checkParentIdIsValid(parentId);
    setIntField(recordId, RecordLayout.PARENT_REF_OFFSET, parentId);
  }

  @Override
  public int getNameId(final int recordId) throws IOException {
    return getIntField(recordId, RecordLayout.NAME_REF_OFFSET);
  }

  @Override
  public int updateNameId(final int recordId,
                          final int nameId) throws IOException {
    PersistentFSConnection.ensureIdIsValid(nameId);
    return getAndSetIntField(recordId, RecordLayout.NAME_REF_OFFSET, nameId);
  }

  @Override
  public boolean setFlags(final int recordId,
                          final @PersistentFS.Attributes int newFlags) throws IOException {
    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final Page page = storage.pageByOffset(recordOffsetInFile);
    final ByteBuffer pageBuffer = page.rawPageBuffer();

    return setIntFieldIfChanged(pageBuffer, recordOffsetOnPage, RecordLayout.FLAGS_OFFSET, newFlags);
  }

  @Override
  public @PersistentFS.Attributes int getFlags(final int recordId) throws IOException {
    //noinspection MagicConstant
    return getIntField(recordId, RecordLayout.FLAGS_OFFSET);
  }

  @Override
  public long getLength(final int recordId) throws IOException {
    return getLongField(recordId, RecordLayout.LENGTH_OFFSET);
  }

  @Override
  public boolean setLength(final int recordId,
                           final long newLength) throws IOException {
    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final int fieldOffsetOnPage = recordOffsetOnPage + RecordLayout.LENGTH_OFFSET;
    final Page page = storage.pageByOffset(recordOffsetInFile);
    final ByteBuffer pageBuffer = page.rawPageBuffer();
    final long storedLength = (long)LONG_HANDLE.getVolatile(pageBuffer, fieldOffsetOnPage);
    if (storedLength != newLength) {
      setLongVolatile(pageBuffer, fieldOffsetOnPage, newLength);
      incrementRecordVersion(pageBuffer, recordOffsetOnPage);

      return true;
    }
    return false;
  }


  @Override
  public long getTimestamp(final int recordId) throws IOException {
    return getLongField(recordId, RecordLayout.TIMESTAMP_OFFSET);
  }

  @Override
  public boolean setTimestamp(final int recordId,
                              final long newTimestamp) throws IOException {
    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final Page page = storage.pageByOffset(recordOffsetInFile);
    final ByteBuffer pageBuffer = page.rawPageBuffer();

    return setLongFieldIfChanged(pageBuffer, recordOffsetOnPage, RecordLayout.TIMESTAMP_OFFSET, newTimestamp);
  }

  @Override
  public int getModCount(final int recordId) throws IOException {
    return getIntField(recordId, RecordLayout.MOD_COUNT_OFFSET);
  }

  @Override
  public int getContentRecordId(final int recordId) throws IOException {
    return getIntField(recordId, RecordLayout.CONTENT_REF_OFFSET);
  }

  @Override
  public boolean setContentRecordId(final int recordId,
                                    final int newContentRecordId) throws IOException {
    checkValidIdField(recordId, newContentRecordId, "contentRecordId");
    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final Page page = storage.pageByOffset(recordOffsetInFile);
    final ByteBuffer pageBuffer = page.rawPageBuffer();
    return setIntFieldIfChanged(pageBuffer, recordOffsetOnPage, RecordLayout.CONTENT_REF_OFFSET, newContentRecordId);
  }

  @Override
  public void fillRecord(final int recordId,
                         final long timestamp,
                         final long length,
                         final int flags,
                         final int nameId,
                         final int parentId,
                         final boolean cleanAttributeRef) throws IOException {
    checkParentIdIsValid(parentId);

    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final Page page = storage.pageByOffset(recordOffsetInFile);
    final ByteBuffer pageBuffer = page.rawPageBuffer();
    setIntVolatile(pageBuffer, recordOffsetOnPage + RecordLayout.PARENT_REF_OFFSET, parentId);
    setIntVolatile(pageBuffer, recordOffsetOnPage + RecordLayout.NAME_REF_OFFSET, nameId);
    setIntVolatile(pageBuffer, recordOffsetOnPage + RecordLayout.FLAGS_OFFSET, flags);
    if (cleanAttributeRef) {
      setIntVolatile(pageBuffer, recordOffsetOnPage + RecordLayout.ATTR_REF_OFFSET, 0);
    }
    //TODO RC: why not set contentId?
    setLongVolatile(pageBuffer, recordOffsetOnPage + RecordLayout.TIMESTAMP_OFFSET, timestamp);
    setLongVolatile(pageBuffer, recordOffsetOnPage + RecordLayout.LENGTH_OFFSET, length);

    incrementRecordVersion(pageBuffer, recordOffsetOnPage);
  }

  @Override
  public void markRecordAsModified(final int recordId) throws IOException {
    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final Page page = storage.pageByOffset(recordOffsetInFile);
    incrementRecordVersion(page.rawPageBuffer(), recordOffsetOnPage);
  }

  @Override
  public void cleanRecord(final int recordId) throws IOException {
    checkRecordIdIsValid(recordId);

    //fill record with zeroes, by 4 bytes at once:
    assert RecordLayout.RECORD_SIZE_IN_BYTES % Integer.BYTES == 0
      : "RECORD_SIZE_IN_BYTES(=" + RecordLayout.RECORD_SIZE_IN_BYTES + ") is expected to be 32-aligned";

    final int recordSizeInInts = RecordLayout.RECORD_SIZE_IN_BYTES / Integer.BYTES;

    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final Page page = storage.pageByOffset(recordOffsetInFile);
    final ByteBuffer pageBuffer = page.rawPageBuffer();
    for (int wordNo = 0; wordNo < recordSizeInInts; wordNo++) {
      final int offsetOfWord = recordOffsetOnPage + wordNo * Integer.BYTES;
      setIntVolatile(pageBuffer, offsetOfWord, 0);
    }
  }


  @Override
  public boolean processAllRecords(final @NotNull PersistentFSRecordsStorage.FsRecordProcessor processor) throws IOException {
    final int recordsCount = maxAllocatedID();
    for (int recordId = MIN_VALID_ID; recordId <= recordsCount; recordId++) {
      processor.process(
        recordId,
        getNameId(recordId),
        getFlags(recordId),
        getParent(recordId),
        getAttributeRecordId(recordId),
        getContentRecordId(recordId),
        /* corrupted = */ false
      );
    }
    return true;
  }


  // ============== storage 'global' properties accessors: ============================= //

  @Override
  public long getTimestamp() {
    return getLongHeaderField(HEADER_TIMESTAMP_OFFSET);
  }

  @Override
  public void setConnectionStatus(final int connectionStatus) {
    setIntHeaderField(HEADER_CONNECTION_STATUS_OFFSET, connectionStatus);
    dirty.compareAndSet(false, true);
  }

  @Override
  public int getConnectionStatus() {
    return getIntHeaderField(HEADER_CONNECTION_STATUS_OFFSET);
  }

  @Override
  public int getErrorsAccumulated() {
    return getIntHeaderField(HEADER_ERRORS_ACCUMULATED_OFFSET);
  }

  @Override
  public void setErrorsAccumulated(int errors) {
    setIntHeaderField(HEADER_ERRORS_ACCUMULATED_OFFSET, errors);
    globalModCount.incrementAndGet();
    dirty.compareAndSet(false, true);
  }

  @Override
  public void setVersion(final int version) {
    setIntHeaderField(HEADER_VERSION_OFFSET, version);
    setLongHeaderField(HEADER_TIMESTAMP_OFFSET, System.currentTimeMillis());
    globalModCount.incrementAndGet();
    dirty.compareAndSet(false, true);
  }

  @Override
  public int getVersion() throws IOException {
    return getIntHeaderField(HEADER_VERSION_OFFSET);
  }

  @Override
  public int getGlobalModCount() {
    return globalModCount.get();
  }

  @Override
  public int recordsCount() {
    return allocatedRecordsCount();
  }

  @Override
  public int maxAllocatedID() {
    //We assign id starting with 1 (id=0 is reserved as NULL_ID)
    // => (maxId == recordsCount)
    return allocatedRecordsCount();
  }

  @Override
  public boolean isValidFileId(int fileId) {
    if (fileId <= NULL_ID) {
      return false;
    }
    int cachedMaxAllocatedID = this.cachedMaxAllocatedId;
    if (fileId <= cachedMaxAllocatedID) {
      return true;
    }
    //'slow path' is extracted into dedicated method to reduce this method (=fast path) bytecode size,
    // and convince JIT inline it. Slow path inlining is not so important, since it is anyway slow.
    // Actually slow path is also inlined, but only after detected to be 'hot', which takes time.
    // So this 'optimization' mostly helps with speed on startup/warmup  -- which is important for us,
    // and especially with transition to jdk21, there JIT seems to have longer warmup before jumping
    // to full C2
    return isValidFileIdStrict(fileId);
  }

  private boolean isValidFileIdStrict(int fileId) {
    int actualMaxAllocatedID = maxAllocatedID();
    this.cachedMaxAllocatedId = Math.max(cachedMaxAllocatedId, actualMaxAllocatedID);
    if (fileId <= actualMaxAllocatedID) {
      return true;
    }
    return false;
  }

  @Override
  public boolean isDirty() {
    return dirty.get();
  }

  @Override
  public void force() throws IOException {
    if (dirty.compareAndSet(true, false)) {
      setIntHeaderField(HEADER_GLOBAL_MOD_COUNT_OFFSET, globalModCount.get());
      if (MMappedFileStorage.FSYNC_ON_FLUSH_BY_DEFAULT) {
        storage.fsync();
      }
    }
  }

  @Override
  public void close() throws IOException {
    force();
    storage.close();
    headerPage = null;
  }

  @Override
  public void closeAndUnsafelyUnmap() throws IOException {
    close();
    storage.closeAndUnsafelyUnmap();
  }

  /** Close the storage and remove all its data files */
  @Override
  public void closeAndClean() throws IOException {
    close();
    storage.closeAndClean();
  }

  // =============== implementation: addressing ========================================================= //

  /** Without recordId bounds checking */
  @VisibleForTesting
  long recordOffsetInFileUnchecked(final int recordId) {
    //recordId is 1-based, convert to 0-based recordNo:
    final int recordNo = recordId - 1;

    if (recordNo < recordsOnHeaderPage) {
      return HEADER_SIZE + recordNo * (long)RecordLayout.RECORD_SIZE_IN_BYTES;
    }

    //as-if there were no header:
    final int fullPages = recordNo / recordsPerPage;
    final int recordsOnLastPage = recordNo % recordsPerPage;

    //header on the first page "pushes out" few records:
    final int recordsExcessBecauseOfHeader = recordsPerPage - recordsOnHeaderPage;

    //so the last page could turn into +1 page:
    final int recordsReallyOnLastPage = recordsOnLastPage + recordsExcessBecauseOfHeader;
    return (long)(fullPages + recordsReallyOnLastPage / recordsPerPage) * pageSize
           + (long)(recordsReallyOnLastPage % recordsPerPage) * RecordLayout.RECORD_SIZE_IN_BYTES;
  }

  private long recordOffsetInFile(final int recordId) throws IndexOutOfBoundsException {
    checkRecordIdIsValid(recordId);
    return recordOffsetInFileUnchecked(recordId);
  }


  private void checkRecordIdIsValid(int recordId) throws IndexOutOfBoundsException {
    if (!isValidFileId(recordId)) {
      throw new IndexOutOfBoundsException(
        "recordId(=" + recordId + ") is outside of allocated IDs range (0, " + maxAllocatedID() + "]");
    }
  }

  private void checkParentIdIsValid(int parentId) throws IndexOutOfBoundsException {
    if (parentId == NULL_ID) {
      //parentId could be NULL (for root records) -- this is the difference with checkRecordIdIsValid()
      return;
    }
    if (!isValidFileId(parentId)) {
      throw new IndexOutOfBoundsException(
        "parentId(=" + parentId + ") is outside of allocated IDs range [0, " + maxAllocatedID() + "]");
    }
  }

  private static void checkValidIdField(int recordId,
                                        int idFieldValue,
                                        @NotNull String fieldName) {
    if (idFieldValue < NULL_ID) {
      throw new IllegalArgumentException("file[id: " + recordId + "]." + fieldName + "(=" + idFieldValue + ") must be >=0");
    }
  }

  // =============== implementation: record field access ================================================ //

  /**
   * How many records were allocated already. Since id=0 is reserved (NULL_ID), we start assigning ids from 1,
   * and hence (last record id == allocatedRecordsCount)
   */
  private int allocatedRecordsCount() {
    return getIntHeaderField(HEADER_RECORDS_ALLOCATED);
  }

  private void setLongField(final int recordId,
                            @FieldOffset final int fieldRelativeOffset,
                            final long fieldValue) throws IOException {
    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final Page page = storage.pageByOffset(recordOffsetInFile);
    final ByteBuffer pageBuffer = page.rawPageBuffer();
    setLongVolatile(pageBuffer, recordOffsetOnPage + fieldRelativeOffset, fieldValue);
    incrementRecordVersion(pageBuffer, recordOffsetOnPage);
  }

  private long getLongField(final int recordId,
                            @FieldOffset final int fieldRelativeOffset) throws IOException {
    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final Page page = storage.pageByOffset(recordOffsetInFile);
    final ByteBuffer pageBuffer = page.rawPageBuffer();
    return (long)LONG_HANDLE.getVolatile(pageBuffer, recordOffsetOnPage + fieldRelativeOffset);
  }

  private boolean setLongFieldIfChanged(final ByteBuffer pageBuffer,
                                        final int recordOffsetOnPage,
                                        @FieldOffset final int fieldRelativeOffset,
                                        final long newValue) {
    final int fieldOffsetOnPage = recordOffsetOnPage + fieldRelativeOffset;
    final long oldValue = (long)LONG_HANDLE.getVolatile(pageBuffer, fieldOffsetOnPage);
    if (oldValue != newValue) {
      setLongVolatile(pageBuffer, fieldOffsetOnPage, newValue);
      incrementRecordVersion(pageBuffer, recordOffsetOnPage);
      return true;
    }
    return false;
  }


  private void setIntField(final int recordId,
                           @FieldOffset final int fieldRelativeOffset,
                           final int fieldValue) throws IOException {
    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final Page page = storage.pageByOffset(recordOffsetInFile);
    final ByteBuffer pageBuffer = page.rawPageBuffer();
    setIntVolatile(pageBuffer, recordOffsetOnPage + fieldRelativeOffset, fieldValue);
    incrementRecordVersion(pageBuffer, recordOffsetOnPage);
  }

  private int getAndSetIntField(final int recordId,
                                @FieldOffset final int fieldRelativeOffset,
                                final int fieldValue) throws IOException {
    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final Page page = storage.pageByOffset(recordOffsetInFile);
    final ByteBuffer pageBuffer = page.rawPageBuffer();
    int previousValue = getAndSetIntVolatile(pageBuffer, recordOffsetOnPage + fieldRelativeOffset, fieldValue);
    incrementRecordVersion(pageBuffer, recordOffsetOnPage);
    return previousValue;
  }

  private int getIntField(final int recordId,
                          @FieldOffset final int fieldRelativeOffset) throws IOException {
    final long recordOffsetInFile = recordOffsetInFile(recordId);
    final int recordOffsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
    final Page page = storage.pageByOffset(recordOffsetInFile);
    final ByteBuffer pageBuffer = page.rawPageBuffer();
    return (int)INT_HANDLE.getVolatile(pageBuffer, recordOffsetOnPage + fieldRelativeOffset);
  }

  private boolean setIntFieldIfChanged(final ByteBuffer pageBuffer,
                                       final int recordOffsetOnPage,
                                       final int fieldRelativeOffset,
                                       final int newValue) {
    final int fieldOffsetOnPage = recordOffsetOnPage + fieldRelativeOffset;
    final int oldValue = (int)INT_HANDLE.getVolatile(pageBuffer, fieldOffsetOnPage);
    if (oldValue != newValue) {
      setIntVolatile(pageBuffer, fieldOffsetOnPage, newValue);
      incrementRecordVersion(pageBuffer, recordOffsetOnPage);

      return true;
    }
    return false;
  }


  private void incrementRecordVersion(final @NotNull ByteBuffer pageBuffer,
                                      final int recordOffsetOnPage) {
    setIntVolatile(pageBuffer, recordOffsetOnPage + RecordLayout.MOD_COUNT_OFFSET, globalModCount.incrementAndGet());
    dirty.compareAndSet(false, true);
  }

  //============ header fields access: ============================================================ //

  private void setLongHeaderField(final @HeaderOffset int headerRelativeOffsetBytes,
                                  final long headerValue) {
    checkHeaderOffset(headerRelativeOffsetBytes);
    setLongVolatile(headerPage().rawPageBuffer(), headerRelativeOffsetBytes, headerValue);
  }

  private long getLongHeaderField(final @HeaderOffset int headerRelativeOffsetBytes) {
    checkHeaderOffset(headerRelativeOffsetBytes);
    return (long)LONG_HANDLE.getVolatile(headerPage().rawPageBuffer(), headerRelativeOffsetBytes);
  }

  private void setIntHeaderField(final @HeaderOffset int headerRelativeOffsetBytes,
                                 final int headerValue) {
    checkHeaderOffset(headerRelativeOffsetBytes);
    setIntVolatile(headerPage().rawPageBuffer(), headerRelativeOffsetBytes, headerValue);
  }


  private int getIntHeaderField(final @HeaderOffset int headerRelativeOffsetBytes) {
    checkHeaderOffset(headerRelativeOffsetBytes);
    return (int)INT_HANDLE.getVolatile(headerPage().rawPageBuffer(), headerRelativeOffsetBytes);
  }

  private Page headerPage() {
    Page page = headerPage;
    if (page == null) {
      throw new AlreadyDisposedException("File records storage is already closed");
    }
    return page;
  }

  private static void checkHeaderOffset(final int headerRelativeOffset) {
    if (!(0 <= headerRelativeOffset && headerRelativeOffset < HEADER_SIZE)) {
      throw new IndexOutOfBoundsException(
        "headerFieldOffset(=" + headerRelativeOffset + ") is outside of header [0, " + HEADER_SIZE + ") ");
    }
  }


  private static void setIntVolatile(final ByteBuffer pageBuffer,
                                     final int offsetInBuffer,
                                     final int value) {
    INT_HANDLE.setVolatile(pageBuffer, offsetInBuffer, value);
  }

  private static int getAndSetIntVolatile(final ByteBuffer pageBuffer,
                                          final int offsetInBuffer,
                                          final int value) {
    return (int)INT_HANDLE.getAndSet(pageBuffer, offsetInBuffer, value);
  }

  private static void setLongVolatile(final ByteBuffer pageBuffer,
                                      final int offsetInBuffer,
                                      final long value) {
    LONG_HANDLE.setVolatile(pageBuffer, offsetInBuffer, value);
  }

  // ========================== debug/diagnostics ========================================================= //

  private void checkUnAllocatedRegionIsZeroed(int recordsToCheck) throws IOException {
    int maxAllocatedID = maxAllocatedID();
    int firstUnAllocatedId = maxAllocatedID + 1;
    long unallocatedRegionStartingOffsetInFile = recordOffsetInFileUnchecked(firstUnAllocatedId);
    int unallocatedRegionStartingOffsetOnPage = storage.toOffsetInPage(unallocatedRegionStartingOffsetInFile);
    long actualFileSize = storage.actualFileSize();
    if (unallocatedRegionStartingOffsetOnPage >= actualFileSize) {
      return;//un-allocated file region is definitely empty
    }

    Page lastPage = storage.pageByOffset(unallocatedRegionStartingOffsetInFile);
    ByteBuffer lastPageBuffer = lastPage.rawPageBuffer();

    int maxBytesRemainsOnPage = lastPageBuffer.limit() - unallocatedRegionStartingOffsetOnPage;
    int bytesToCheck = Math.min(
      recordsToCheck * RecordLayout.RECORD_SIZE_IN_BYTES,
      maxBytesRemainsOnPage
    );

    int firstNonZeroOffsetInPage = firstNonZeroByteOffset(lastPageBuffer, unallocatedRegionStartingOffsetOnPage, bytesToCheck);
    if (firstNonZeroOffsetInPage >= 0) {
      //if we already found non-0 record, no reason for economy:
      // => better to collect AMAP diagnostic info
      // => lets check more bytes (but not too many: i.e. scanning 64Mb byte-by-byte could be quite offensive for UX,
      // and for our TeamCity tests as well!)
      int bytesToCheckAdditionally = Math.min(maxBytesRemainsOnPage, 1 << 16);
      int lastNonZeroOffsetInPage = lastNonZeroByteOffset(lastPageBuffer, unallocatedRegionStartingOffsetOnPage, bytesToCheckAdditionally);
      int nonZeroBytesBeyondEOF = lastNonZeroOffsetInPage - unallocatedRegionStartingOffsetOnPage + 1;
      int nonZeroedRecordsCount = (nonZeroBytesBeyondEOF / RecordLayout.RECORD_SIZE_IN_BYTES) + 1;

      throw new CorruptedException(
        "Non-empty records detected beyond current EOF => storage is corrupted.\n" +
        "\tmax allocated id(=" + maxAllocatedID + ")\n" +
        "\tfirst un-allocated offset: " + unallocatedRegionStartingOffsetInFile + "\n" +
        "\tcontent beyond allocated region(" + recordsToCheck + " records max): \n" +
        dumpRecordsAsHex(firstUnAllocatedId, firstUnAllocatedId + recordsToCheck) + "\n" +
        "=" + nonZeroedRecordsCount + " total non-zero records on the page, in range " +
        "[" + unallocatedRegionStartingOffsetInFile + ".." + (unallocatedRegionStartingOffsetInFile + nonZeroBytesBeyondEOF) + ")"
      );
    }
  }

  /**
   * @return offset of the first non-zero byte in a range [startingOffset..startingOffset+maxBytesToCheck),
   * or -1 if all bytes in the range are 0
   */
  private static int firstNonZeroByteOffset(@NotNull ByteBuffer buffer,
                                            int startingOffset,
                                            int maxBytesToCheck) {
    for (int i = 0; i < maxBytesToCheck; i++) {
      byte b = buffer.get(startingOffset + i);
      if (b != 0) {
        return startingOffset + i;
      }
    }
    return -1;
  }

  /**
   * @return offset of the last non-zero byte in a range [startingOffset..startingOffset+maxBytesToCheck),
   * or -1 if all bytes in the range are 0
   */
  private static int lastNonZeroByteOffset(@NotNull ByteBuffer buffer,
                                           int startingOffset,
                                           int maxBytesToCheck) {
    int lastNonZeroOffset = -1;
    for (int i = 0; i < maxBytesToCheck; i++) {
      byte b = buffer.get(startingOffset + i);
      if (b != 0) {
        lastNonZeroOffset = startingOffset + i;
      }
    }
    return lastNonZeroOffset;
  }

  /**
   * Method is for debugging/monitoring purposes
   *
   * @return records [firstRecordId..lastRecordId] (both ends inclusive) hex-formatted, one per line
   */
  public String dumpRecordsAsHex(int firstRecordId,
                                 int lastRecordId) throws IOException {
    if (firstRecordId > lastRecordId) {
      return "<no records in range " + firstRecordId + " .. " + lastRecordId + ">";
    }
    long actualFileSize = storage.actualFileSize();
    StringBuilder sb = new StringBuilder();
    for (int recordId = firstRecordId; recordId <= lastRecordId; recordId++) {
      String recordAsHex;
      if (recordId == NULL_ID) {
        recordAsHex = "<header>";
      }
      else {
        long recordOffsetInFile = recordOffsetInFileUnchecked(recordId);

        if (recordOffsetInFile >= actualFileSize) {
          recordAsHex = "<EOF: outside of allocated file region>";
        }
        else {
          int recordOffsetInPage = storage.toOffsetInPage(recordOffsetInFile);

          Page page = storage.pageByOffset(recordOffsetInFile);
          ByteBuffer pageBuffer = page.rawPageBuffer();
          ByteBuffer recordSlice = pageBuffer.slice(recordOffsetInPage, RecordLayout.RECORD_SIZE_IN_BYTES);

          recordAsHex = IOUtil.toHexString(recordSlice);
        }
      }
      sb.append("[#%06d/max=%06d]: ".formatted(recordId, maxAllocatedID()))
        .append(recordAsHex)
        .append('\n');
    }
    return sb.toString();
  }

  @MagicConstant(flagsFromClass = RecordLayout.class)
  @Target(ElementType.TYPE_USE)
  public @interface FieldOffset {
  }
}
