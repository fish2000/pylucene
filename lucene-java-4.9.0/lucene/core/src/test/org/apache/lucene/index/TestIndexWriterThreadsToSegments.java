package org.apache.lucene.index;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.Closeable;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.lucene.analysis.MockAnalyzer;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.lucene46.Lucene46SegmentInfoFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.TestUtil;

public class TestIndexWriterThreadsToSegments extends LuceneTestCase {

  // LUCENE-5644: for first segment, two threads each indexed one doc (likely concurrently), but for second segment, each thread indexed the
  // doc NOT at the same time, and should have shared the same thread state / segment
  public void testSegmentCountOnFlushBasic() throws Exception {
    Directory dir = newDirectory();
    final IndexWriter w = new IndexWriter(dir, new IndexWriterConfig(TEST_VERSION_CURRENT, new MockAnalyzer(random())));
    final CountDownLatch startingGun = new CountDownLatch(1);
    final CountDownLatch startDone = new CountDownLatch(2);
    final CountDownLatch middleGun = new CountDownLatch(1);
    final CountDownLatch finalGun = new CountDownLatch(1);
    Thread[] threads = new Thread[2];
    for(int i=0;i<threads.length;i++) {
      final int threadID = i;
      threads[i] = new Thread() {
          @Override
          public void run() {
            try {
              startingGun.await();
              Document doc = new Document();
              doc.add(newTextField("field", "here is some text", Field.Store.NO));
              w.addDocument(doc);
              startDone.countDown();

              middleGun.await();
              if (threadID == 0) {
                w.addDocument(doc);
              } else {
                finalGun.await();
                w.addDocument(doc);
              }
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        };
      threads[i].start();
    }

    startingGun.countDown();
    startDone.await();

    IndexReader r = DirectoryReader.open(w, true);
    assertEquals(2, r.numDocs());
    int numSegments = r.leaves().size();
    // 1 segment if the threads ran sequentially, else 2:
    assertTrue(numSegments <= 2);
    r.close();

    middleGun.countDown();
    threads[0].join();

    finalGun.countDown();
    threads[1].join();

    r = DirectoryReader.open(w, true);
    assertEquals(4, r.numDocs());
    // Both threads should have shared a single thread state since they did not try to index concurrently:
    assertEquals(1+numSegments, r.leaves().size());
    r.close();

    w.close();
    dir.close();
  }

  /** Maximum number of simultaneous threads to use for each iteration. */
  private static final int MAX_THREADS_AT_ONCE = 10;

  static class CheckSegmentCount implements Runnable, Closeable {
    private final IndexWriter w;
    private final AtomicInteger maxThreadCountPerIter;
    private final AtomicInteger indexingCount;
    private DirectoryReader r;

    public CheckSegmentCount(IndexWriter w, AtomicInteger maxThreadCountPerIter, AtomicInteger indexingCount) throws IOException {
      this.w = w;
      this.maxThreadCountPerIter = maxThreadCountPerIter;
      this.indexingCount = indexingCount;
      r = DirectoryReader.open(w, true);
      assertEquals(0, r.leaves().size());
      setNextIterThreadCount();
    }

    @Override
    public void run() {
      try {
        int oldSegmentCount = r.leaves().size();
        DirectoryReader r2 = DirectoryReader.openIfChanged(r);
        assertNotNull(r2);
        r.close();
        r = r2;
        int maxThreadStates = w.getConfig().getMaxThreadStates();
        int maxExpectedSegments = oldSegmentCount + Math.min(maxThreadStates, maxThreadCountPerIter.get());
        if (VERBOSE) {
          System.out.println("TEST: iter done; now verify oldSegCount=" + oldSegmentCount + " newSegCount=" + r2.leaves().size() + " maxExpected=" + maxExpectedSegments);
        }
        // NOTE: it won't necessarily be ==, in case some threads were strangely scheduled and never conflicted with one another (should be uncommon...?):
        assertTrue(r.leaves().size() <= maxExpectedSegments);
        setNextIterThreadCount();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    private void setNextIterThreadCount() {
      indexingCount.set(0);
      maxThreadCountPerIter.set(TestUtil.nextInt(random(), 1, MAX_THREADS_AT_ONCE));
      if (VERBOSE) {
        System.out.println("TEST: iter set maxThreadCount=" + maxThreadCountPerIter.get());
      }
    }

    @Override
    public void close() throws IOException {
      r.close();
      r = null;
    }
  }

  // LUCENE-5644: index docs w/ multiple threads but in between flushes we limit how many threads can index concurrently in the next
  // iteration, and then verify that no more segments were flushed than number of threads:
  public void testSegmentCountOnFlushRandom() throws Exception {
    Directory dir = newFSDirectory(createTempDir());
    IndexWriterConfig iwc = new IndexWriterConfig(TEST_VERSION_CURRENT, new MockAnalyzer(random()));

    int maxThreadStates = TestUtil.nextInt(random(), 1, 12);

    if (VERBOSE) {
      System.out.println("TEST: maxThreadStates=" + maxThreadStates);
    }

    // Never trigger flushes (so we only flush on getReader):
    iwc.setMaxBufferedDocs(100000000);
    iwc.setRAMBufferSizeMB(-1);
    iwc.setMaxThreadStates(maxThreadStates);

    // Never trigger merges (so we can simplistically count flushed segments):
    iwc.setMergePolicy(NoMergePolicy.INSTANCE);

    final IndexWriter w = new IndexWriter(dir, iwc);

    // How many threads are indexing in the current cycle:
    final AtomicInteger indexingCount = new AtomicInteger();

    // How many threads we will use on each cycle:
    final AtomicInteger maxThreadCount = new AtomicInteger();

    CheckSegmentCount checker = new CheckSegmentCount(w, maxThreadCount, indexingCount);

    // We spin up 10 threads up front, but then in between flushes we limit how many can run on each iteration
    final int ITERS = 100;
    Thread[] threads = new Thread[MAX_THREADS_AT_ONCE];

    // We use this to stop all threads once they've indexed their docs in the current iter, and pull a new NRT reader, and verify the
    // segment count:
    final CyclicBarrier barrier = new CyclicBarrier(MAX_THREADS_AT_ONCE, checker);
    
    for(int i=0;i<threads.length;i++) {
      threads[i] = new Thread() {
          @Override
          public void run() {
            try {
              for(int iter=0;iter<ITERS;iter++) {
                if (indexingCount.incrementAndGet() <= maxThreadCount.get()) {
                  if (VERBOSE) {
                    System.out.println("TEST: " + Thread.currentThread().getName() + ": do index");
                  }

                  // We get to index on this cycle:
                  Document doc = new Document();
                  doc.add(new TextField("field", "here is some text that is a bit longer than normal trivial text", Field.Store.NO));
                  for(int j=0;j<200;j++) {
                    w.addDocument(doc);
                  }
                } else {
                  // We lose: no indexing for us on this cycle
                  if (VERBOSE) {
                    System.out.println("TEST: " + Thread.currentThread().getName() + ": don't index");
                  }
                }
                barrier.await();
              }
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        };
      threads[i].start();
    }

    for(Thread t : threads) {
      t.join();
    }

    IOUtils.close(checker, w, dir);
  }

  public void testManyThreadsClose() throws Exception {
    Directory dir = newDirectory();
    final RandomIndexWriter w = new RandomIndexWriter(random(), dir);
    w.setDoRandomForceMerge(false);
    Thread[] threads = new Thread[TestUtil.nextInt(random(), 4, 30)];
    final CountDownLatch startingGun = new CountDownLatch(1);
    for(int i=0;i<threads.length;i++) {
      threads[i] = new Thread() {
          @Override
          public void run() {
            try {
              startingGun.await();
              Document doc = new Document();
              doc.add(new TextField("field", "here is some text that is a bit longer than normal trivial text", Field.Store.NO));
              while (true) {
                w.addDocument(doc);
              }
            } catch (AlreadyClosedException ace) {
              // ok
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        };
      threads[i].start();
    }

    startingGun.countDown();

    Thread.sleep(100);
    w.close();
    for(Thread t : threads) {
      t.join();
    }
    dir.close();
  }

  public void testDocsStuckInRAMForever() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(TEST_VERSION_CURRENT, new MockAnalyzer(random()));
    iwc.setRAMBufferSizeMB(.2);
    Codec codec = Codec.forName("Lucene49");
    iwc.setCodec(codec);
    iwc.setMergePolicy(NoMergePolicy.INSTANCE);
    final IndexWriter w = new IndexWriter(dir, iwc);
    final CountDownLatch startingGun = new CountDownLatch(1);
    Thread[] threads = new Thread[2];
    for(int i=0;i<threads.length;i++) {
      final int threadID = i;
      threads[i] = new Thread() {
          @Override
          public void run() {
            try {
              startingGun.await();
              for(int j=0;j<1000;j++) {
                Document doc = new Document();
                doc.add(newStringField("field", "threadID" + threadID, Field.Store.NO));
                w.addDocument(doc);
              }
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        };
      threads[i].start();
    }

    startingGun.countDown();
    for(Thread t : threads) {
      t.join();
    }

    Set<String> segSeen = new HashSet<>();
    int thread0Count = 0;
    int thread1Count = 0;

    // At this point the writer should have 2 thread states w/ docs; now we index with only 1 thread until we see all 1000 thread0 & thread1
    // docs flushed.  If the writer incorrectly holds onto previously indexed docs forever then this will run forever:
    while (thread0Count < 1000 || thread1Count < 1000) {
      Document doc = new Document();
      doc.add(newStringField("field", "threadIDmain", Field.Store.NO));
      w.addDocument(doc);

      for(String fileName : dir.listAll()) {
        if (fileName.endsWith(".si")) {
          String segName = IndexFileNames.parseSegmentName(fileName);
          if (segSeen.contains(segName) == false) {
            segSeen.add(segName);
            SegmentInfo si = new Lucene46SegmentInfoFormat().getSegmentInfoReader().read(dir, segName, IOContext.DEFAULT);
            si.setCodec(codec);
            SegmentCommitInfo sci = new SegmentCommitInfo(si, 0, -1, -1, -1);
            SegmentReader sr = new SegmentReader(sci, 1, IOContext.DEFAULT);
            try {
              thread0Count += sr.docFreq(new Term("field", "threadID0"));
              thread1Count += sr.docFreq(new Term("field", "threadID1"));
            } finally {
              sr.close();
            }
          }
        }
      }
    }

    w.close();
    dir.close();
  }
}
