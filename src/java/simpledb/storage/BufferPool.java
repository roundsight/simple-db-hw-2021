package simpledb.storage;

import simpledb.common.Database;
import simpledb.common.Permissions;
import simpledb.common.DbException;
import simpledb.common.DeadlockException;
import simpledb.index.BTreeInternalPage;
import simpledb.index.BTreePage;
import simpledb.index.BTreePageId;
import simpledb.transaction.TransactionAbortedException;
import simpledb.transaction.TransactionId;

import javax.xml.crypto.Data;
import java.io.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BufferPool manages the reading and writing of pages into memory from
 * disk. Access methods call into it to retrieve pages, and it fetches
 * pages from the appropriate location.
 * <p>
 * The BufferPool is also responsible for locking;  when a transaction fetches
 * a page, BufferPool checks that the transaction has the appropriate
 * locks to read/write the page.
 * 
 * @Threadsafe, all fields are final
 */
public class BufferPool {
    /** Bytes per page, including header. */
    private static final int DEFAULT_PAGE_SIZE = 4096;

    private static int pageSize = DEFAULT_PAGE_SIZE;
    
    /** Default number of pages passed to the constructor. This is used by
    other classes. BufferPool should use the numPages argument to the
    constructor instead. */
    public static final int DEFAULT_PAGES = 50;

    private final int numPages;
    private final ConcurrentHashMap<PageId, Page> pages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TransactionId, Set<PageId>> txnPages = new ConcurrentHashMap<>();
    private final List<PageId> lru;
    private final LockManager lockMgr = new LockManager();

    /**
     * Creates a BufferPool that caches up to numPages pages.
     *
     * @param numPages maximum number of pages in this buffer pool.
     */
    public BufferPool(int numPages) {
        // some code goes here
        this.numPages = numPages;
        lru = new CopyOnWriteArrayList<>();
    }
    
    public static int getPageSize() {
      return pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void setPageSize(int pageSize) {
    	BufferPool.pageSize = pageSize;
    }
    
    // THIS FUNCTION SHOULD ONLY BE USED FOR TESTING!!
    public static void resetPageSize() {
    	BufferPool.pageSize = DEFAULT_PAGE_SIZE;
    }

    /**
     * Retrieve the specified page with the associated permissions.
     * Will acquire a lock and may block if that lock is held by another
     * transaction.
     * <p>
     * The retrieved page should be looked up in the buffer pool.  If it
     * is present, it should be returned.  If it is not present, it should
     * be added to the buffer pool and returned.  If there is insufficient
     * space in the buffer pool, a page should be evicted and the new page
     * should be added in its place.
     *
     * @param tid the ID of the transaction requesting the page
     * @param pid the ID of the requested page
     * @param perm the requested permissions on the page
     */
    public Page getPage(TransactionId tid, PageId pid, Permissions perm) throws TransactionAbortedException, DbException {
        lockMgr.lock(tid, pid, perm);
        Page page = null;
        if (pages.containsKey(pid)) {
            page = pages.get(pid);
            lru.remove(pid);
        } else {
            if (pages.size() >= numPages) {
                evictPage();
            }
            page = Database.getCatalog().getDatabaseFile(pid.getTableId()).readPage(pid);
            pages.put(pid, page);
        }
        Set<PageId> pids = txnPages.getOrDefault(tid, new HashSet<>());
        pids.add(pid);
        txnPages.put(tid, pids);
        lru.add(pid);
        return page;
    }

    /**
     * Releases the lock on a page.
     * Calling this is very risky, and may result in wrong behavior. Think hard
     * about who needs to call this and why, and why they can run the risk of
     * calling it.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param pid the ID of the page to unlock
     */
    public void unsafeReleasePage(TransactionId tid, PageId pid) {
        // some code goes here
        // not necessary for lab1|lab2
        lockMgr.unlock(tid, pid);
    }

    /**
     * Release all locks associated with a given transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     */
    public void transactionComplete(TransactionId tid) throws IOException {
        // some code goes here
        // not necessary for lab1|lab2
        transactionComplete(tid, true);
    }

    /** Return true if the specified transaction has a lock on the specified page */
    public boolean holdsLock(TransactionId tid, PageId p) {
        // some code goes here
        // not necessary for lab1|lab2
        return lockMgr.holdsLock(tid, p);
    }

    /**
     * Commit or abort a given transaction; release all locks associated to
     * the transaction.
     *
     * @param tid the ID of the transaction requesting the unlock
     * @param commit a flag indicating whether we should commit or abort
     */
    public void transactionComplete(TransactionId tid, boolean commit) throws IOException {
        // some code goes here
        // not necessary for lab1|lab2
        Set<PageId> pids = txnPages.get(tid);
        if (pids == null) {
            return;
        }
        
        for (PageId pid : pids) {
            if (pages.containsKey(pid)) {
                if (commit) {
                    if (pages.get(pid).isDirty() != null) {
                        flushPage(pid);
                    }
                    pages.get(pid).setBeforeImage();
                } else {
                    Page page = Database.getCatalog().getDatabaseFile(pid.getTableId()).readPage(pid);
                    pages.put(pid, page);
                }
            }

            unsafeReleasePage(tid, pid);
        }

        txnPages.remove(tid);
    }

    /**
     * Add a tuple to the specified table on behalf of transaction tid.  Will
     * acquire a write lock on the page the tuple is added to and any other 
     * pages that are updated (Lock acquisition is not needed for lab2). 
     * May block if the lock(s) cannot be acquired.
     * 
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction adding the tuple
     * @param tableId the table to add the tuple to
     * @param t the tuple to add
     */
    public void insertTuple(TransactionId tid, int tableId, Tuple t) throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        // not necessary for lab1
        List<Page> pages = Database.getCatalog().getDatabaseFile(tableId).insertTuple(tid, t);
        for (Page page : pages) {
            page.markDirty(true, tid);
            PageId id = page.getId();
            if (!this.pages.containsKey(id)) {
                getPage(tid, page.getId(), Permissions.READ_WRITE);
            }
            this.pages.put(id, page);
        }
    }

    /**
     * Remove the specified tuple from the buffer pool.
     * Will acquire a write lock on the page the tuple is removed from and any
     * other pages that are updated. May block if the lock(s) cannot be acquired.
     *
     * Marks any pages that were dirtied by the operation as dirty by calling
     * their markDirty bit, and adds versions of any pages that have 
     * been dirtied to the cache (replacing any existing versions of those pages) so 
     * that future requests see up-to-date pages. 
     *
     * @param tid the transaction deleting the tuple.
     * @param t the tuple to delete
     */
    public void deleteTuple(TransactionId tid, Tuple t) throws DbException, IOException, TransactionAbortedException {
        // some code goes here
        // not necessary for lab1
        int tableId = t.getRecordId().getPageId().getTableId();
        List<Page> pages = Database.getCatalog().getDatabaseFile(tableId).deleteTuple(tid, t);
        for (Page page : pages) {
            page.markDirty(true, tid);
            PageId id = page.getId();
            if (!this.pages.containsKey(id)) {
                getPage(tid, page.getId(), Permissions.READ_WRITE);
            }
            this.pages.put(id, page);
        }
    }

    /**
     * Flush all dirty pages to disk.
     * NB: Be careful using this routine -- it writes dirty data to disk so will
     *     break simpledb if running in NO STEAL mode.
     */
    public synchronized void flushAllPages() throws IOException {
        // some code goes here
        // not necessary for lab1
        for (Map.Entry<PageId, Page> entry : pages.entrySet()) {
            if (entry.getValue().isDirty() != null) {
                flushPage(entry.getKey());
            }
        }
    }

    /** Remove the specific page id from the buffer pool.
        Needed by the recovery manager to ensure that the
        buffer pool doesn't keep a rolled back page in its
        cache.
        
        Also used by B+ tree files to ensure that deleted pages
        are removed from the cache so they can be reused safely
    */
    public synchronized void discardPage(PageId pid) {
        // some code goes here
        // not necessary for lab1
        pages.remove(pid);
        lru.remove(pid);
    }

    /**
     * Flushes a certain page to disk
     * @param pid an ID indicating the page to flush
     */
    private synchronized void flushPage(PageId pid) throws IOException {
        // some code goes here
        // not necessary for lab1
        if (pages.containsKey(pid)) {
            Page page = pages.get(pid);
            TransactionId dirtier = page.isDirty();
            if (dirtier != null){
                // append an update record to the log, with
                // a before-image and after-image.
                Database.getLogFile().logWrite(dirtier, page.getBeforeImage(), page);
                Database.getLogFile().force();

                Database.getCatalog().getDatabaseFile(pid.getTableId()).writePage(page);
                page.markDirty(false, null);
            }
        }
    }

    /** Write all pages of the specified transaction to disk.
     */
    public synchronized void flushPages(TransactionId tid) throws IOException {
        // some code goes here
        // not necessary for lab1|lab2
    }

    /**
     * Discards a page from the buffer pool.
     * Flushes the page to disk to ensure dirty pages are updated on disk.
     */
    private synchronized void evictPage() throws DbException {
        // some code goes here
        // not necessary for lab1
        for (int i = 0; i < lru.size(); i++) {
            if (pages.get(lru.get(i)).isDirty() == null) {
                pages.remove(lru.get(i));
                lru.remove(i);
                return;
            }
        }
        throw new DbException("no page to evict");
    }

}
