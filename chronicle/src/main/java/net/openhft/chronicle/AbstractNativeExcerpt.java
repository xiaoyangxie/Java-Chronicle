/*
 * Copyright 2013 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle;

import net.openhft.lang.io.NativeBytes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sun.nio.ch.DirectBuffer;

import java.io.IOException;
import java.nio.MappedByteBuffer;

/**
 * @author peter.lawrey
 */
public abstract class AbstractNativeExcerpt extends NativeBytes implements ExcerptCommon {
    @NotNull
    protected final IndexedChronicle chronicle;
    final int cacheLineMask;
    final int dataBlockSize;
    final int indexBlockSize;
    final int indexEntriesPerLine;
    final int indexEntriesPerBlock;
    private final int cacheLineSize;
    @Nullable
    @SuppressWarnings("FieldCanBeLocal")
    MappedByteBuffer indexBuffer;
    @Nullable
    @SuppressWarnings("FieldCanBeLocal")
    MappedByteBuffer dataBuffer;
    long index = -1;
    // relatively static
    // the start of the index block, as an address
    long indexStartAddr;
    // which index does this refer to?
    long indexStartOffset;
    // the offset in data referred to the start of the line
    long indexBaseForLine;
    // the start of the data block, as an address
    long dataStartAddr;
    // which offset does this refer to.
    long dataStartOffset;
    // the position currently writing to in the index.
    long indexPositionAddr;
    boolean padding = true;

    // the start of this entry
    // inherited - long startAddr;
    // inherited - long positionAddr;
    // inherited - long limitAddr;

    public AbstractNativeExcerpt(@NotNull IndexedChronicle chronicle) throws IOException {
        super(0, 0, 0);
        this.chronicle = chronicle;
        cacheLineSize = chronicle.config.cacheLineSize();
        cacheLineMask = (cacheLineSize - 1);
        dataBlockSize = chronicle.config.dataBlockSize();
        indexBlockSize = chronicle.config.indexBlockSize();
        indexEntriesPerLine = (cacheLineSize - 8) / 4;
        indexEntriesPerBlock = indexBlockSize * indexEntriesPerLine / cacheLineSize;
        loadIndexBuffer();
        loadDataBuffer();

        finished = true;
    }

    @Override
    public long index() {
        return index;
    }

    public ExcerptCommon toStart() {
        index = -1;
        return this;
    }

    protected boolean indexForRead(long l) {
        if (l < 0) {
            indexBuffer = chronicle.indexFileCache.acquireBuffer(0, true);
            indexStartAddr = ((DirectBuffer) indexBuffer).address();
            indexPositionAddr = indexStartAddr;
            index = -1;
            padding = true;
            return false;
        }
        long indexLookup = l / indexEntriesPerBlock;
        indexBuffer = chronicle.indexFileCache.acquireBuffer(indexLookup, true);
        indexStartAddr = ((DirectBuffer) indexBuffer).address();

        long indexLookupMod = l % indexEntriesPerBlock;
        int indexLineEntry = (int) (indexLookupMod % indexEntriesPerLine);
        int indexLineStart = (int) (indexLookupMod / indexEntriesPerLine * cacheLineSize);
        int inLine = (indexLineEntry << 2) + 8;

        int dataOffsetEnd = UNSAFE.getInt(indexStartAddr + indexLineStart + inLine);

        indexBaseForLine = UNSAFE.getLong(indexStartAddr + indexLineStart);
        indexPositionAddr = indexStartAddr + indexLineStart + inLine;

        long dataOffsetStart = inLine == 0
                ? indexBaseForLine
                : (indexBaseForLine + Math.abs(UNSAFE.getInt(indexPositionAddr - 4)));

        long dataLookup = dataOffsetStart / dataBlockSize;
        long dataLookupMod = dataOffsetStart % dataBlockSize;
        MappedByteBuffer dataMBB = chronicle.dataFileCache.acquireBuffer(dataLookup, true);
        long dataAddr = ((DirectBuffer) dataMBB).address();

        startAddr = positionAddr = dataAddr + dataLookupMod;
        index = l;
        if (dataOffsetEnd > 0) {
            limitAddr = dataAddr + (indexBaseForLine + dataOffsetEnd - dataLookup * dataBlockSize);
            indexPositionAddr += 4;
            padding = false;
            return true;
        } else if (dataOffsetEnd == 0) {
            limitAddr = startAddr;
            padding = false;
            return false;
        } else /* if (dataOffsetEnd < 0) */ {
            padding = true;
            return false;
        }
    }

    protected void indexForAppender(long l) {
        if (l < 0) {
            throw new IndexOutOfBoundsException("index: " + l);
        } else if (l == 0) {
            indexStartOffset = 0;
            loadIndexBuffer();
            dataStartOffset = 0;
            loadDataBuffer();
            return;
        }

        // We need the end of the previous Excerpt
        l--;
        long indexLookup = l / indexEntriesPerBlock;
        indexBuffer = chronicle.indexFileCache.acquireBuffer(indexLookup, true);
        indexStartAddr = ((DirectBuffer) indexBuffer).address();

        long indexLookupMod = l % indexEntriesPerBlock;
        int indexLineEntry = (int) (indexLookupMod % indexEntriesPerLine);
        int indexLineStart = (int) (indexLookupMod / indexEntriesPerLine * cacheLineSize);
        int inLine = (indexLineEntry << 2) + 8;
        indexStartOffset = indexLookup * indexBlockSize + indexLineStart;

        indexBaseForLine = UNSAFE.getLong(indexStartAddr + indexLineStart);
        long dataOffsetEnd = indexBaseForLine + Math.abs(UNSAFE.getInt(indexStartAddr + indexLineStart + inLine));

        long dataLookup = dataOffsetEnd / dataBlockSize;
        long dataLookupMod = dataOffsetEnd % dataBlockSize;
        dataBuffer = chronicle.dataFileCache.acquireBuffer(dataLookup, true);
        dataStartAddr = ((DirectBuffer) dataBuffer).address();
        dataStartOffset = dataLookup * dataBlockSize;
        startAddr = positionAddr = dataStartAddr + dataLookupMod;
        index = l + 1;
        indexPositionAddr = indexStartAddr + indexLineStart + inLine + 4;
    }

    @Override
    public boolean wasPadding() {
        return padding;
    }

    @Override
    public long lastWrittenIndex() {
        return chronicle.lastWrittenIndex();
    }

    @Override
    public long size() {
        return chronicle.size();
    }

    @NotNull
    @Override
    public Chronicle chronicle() {
        return chronicle;
    }

    void loadNextIndexBuffer() {
        indexStartOffset += indexBlockSize;
        loadIndexBuffer();
    }

    void loadNextDataBuffer() {
        dataStartOffset += dataBlockSize;
        loadDataBuffer();
    }

    void loadNextDataBuffer(long offsetInThisBuffer) {
        dataStartOffset += offsetInThisBuffer / dataBlockSize * dataBlockSize;
        loadDataBuffer();

    }

    void loadDataBuffer() {
        dataBuffer = chronicle.dataFileCache.acquireBuffer(dataStartOffset / dataBlockSize, true);
        dataStartAddr = startAddr = positionAddr = limitAddr = ((DirectBuffer) dataBuffer).address();
    }

    void loadIndexBuffer() {
        indexBuffer = chronicle.indexFileCache.acquireBuffer(indexStartOffset / indexBlockSize, true);
        indexStartAddr = indexPositionAddr = ((DirectBuffer) indexBuffer).address();
    }

    public boolean index(long index) {
        throw new UnsupportedOperationException();
    }

    public long findMatch(@NotNull ExcerptComparator comparator) {
        long lo = 0, hi = lastWrittenIndex();
        while (lo <= hi) {
            long mid = (hi + lo) >>> 1;
            if (!index(mid)) {
                if (mid > lo)
                    index(--mid);
                else
                    break;
            }
            int cmp = comparator.compare((Excerpt) this);
            finish();
            if (cmp < 0)
                lo = mid + 1;
            else if (cmp > 0)
                hi = mid - 1;
            else
                return mid; // key found
        }
        return ~lo; // -(lo + 1)
    }

    public void findRange(@NotNull long[] startEnd, @NotNull ExcerptComparator comparator) {
        // lower search range
        long lo1 = 0, hi1 = lastWrittenIndex();
        // upper search range
        long lo2 = 0, hi2 = hi1;
        boolean both = true;
        // search for the low values.
        while (lo1 <= hi1) {
            long mid = (hi1 + lo1) >>> 1;
            if (!index(mid)) {
                if (mid > lo1)
                    index(--mid);
                else
                    break;
            }
            int cmp = comparator.compare((Excerpt) this);
            finish();

            if (cmp < 0) {
                lo1 = mid + 1;
                if (both)
                    lo2 = lo1;
            } else if (cmp > 0) {
                hi1 = mid - 1;
                if (both)
                    hi2 = hi1;
            } else {
                hi1 = mid - 1;
                if (both)
                    lo2 = mid + 1;
                both = false;
            }
        }
        // search for the high values.
        while (lo2 <= hi2) {
            long mid = (hi2 + lo2) >>> 1;
            if (!index(mid)) {
                if (mid > lo2)
                    index(--mid);
                else
                    break;
            }
            int cmp = comparator.compare((Excerpt) this);
            finish();

            if (cmp <= 0) {
                lo2 = mid + 1;
            } else {
                hi2 = mid - 1;
            }
        }
        startEnd[0] = lo1; // inclusive
        startEnd[1] = lo2; // exclusive
    }
}
