/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable
 * law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 * for the specific language governing permissions and limitations under the License.
 */

package org.apache.curator.framework.recipes.cache;

import com.google.common.collect.Lists;
import com.google.common.io.Closeables;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.UnhandledErrorListener;
import org.apache.curator.framework.recipes.BaseClassForTests;
import org.apache.curator.framework.recipes.cache.PathChildrenCache.DescendantHandlingMode;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.KillSession;
import org.apache.curator.test.Timing;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.testng.Assert;
import org.testng.annotations.Test;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class TestPathChildrenCache extends BaseClassForTests
{

    @Test
    public void testPostInitializedForEmpty() throws Exception
    {
        Timing timing = new Timing();
        PathChildrenCache cache = null;
        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
        try
        {
            client.start();

            final CountDownLatch latch = new CountDownLatch(1);
            cache = new PathChildrenCache(client, "/test", true);
            cache.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    if ( event.getType() == PathChildrenCacheEvent.Type.INITIALIZED )
                    {
                        latch.countDown();
                    }
                }
            });
            cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);
            Assert.assertTrue(timing.awaitLatch(latch));
        }
        finally
        {
            Closeables.closeQuietly(cache);
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testAsyncInitialPopulation() throws Exception
    {
        PathChildrenCache cache = null;
        CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), new RetryOneTime(1));
        try
        {
            client.start();

            client.create().forPath("/test");
            client.create().forPath("/test/one", "hey there".getBytes());

            final BlockingQueue<PathChildrenCacheEvent> events = new LinkedBlockingQueue<PathChildrenCacheEvent>();
            cache = new PathChildrenCache(client, "/test", true);
            cache.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    events.offer(event);
                }
            });
            cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);

            PathChildrenCacheEvent event = events.poll(10, TimeUnit.SECONDS);
            Assert.assertEquals(event.getType(), PathChildrenCacheEvent.Type.CHILD_ADDED);

            event = events.poll(10, TimeUnit.SECONDS);
            Assert.assertEquals(event.getType(), PathChildrenCacheEvent.Type.INITIALIZED);
            Assert.assertEquals(event.getInitialData().size(), 1);
        }
        finally
        {
            Closeables.closeQuietly(cache);
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testChildrenInitialized() throws Exception
    {
        Timing timing = new Timing();
        PathChildrenCache cache = null;
        CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(
            1));
        try
        {
            client.start();
            client.create().forPath("/test");

            cache = new PathChildrenCache(client, "/test", true);

            final CountDownLatch addedLatch = new CountDownLatch(3);
            final CountDownLatch initLatch = new CountDownLatch(1);
            cache.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    if ( event.getType() == PathChildrenCacheEvent.Type.CHILD_ADDED )
                    {
                        addedLatch.countDown();
                    }
                    else if ( event.getType() == PathChildrenCacheEvent.Type.INITIALIZED )
                    {
                        initLatch.countDown();
                    }
                }
            });

            client.create().forPath("/test/1", "1".getBytes());
            client.create().forPath("/test/2", "2".getBytes());
            client.create().forPath("/test/3", "3".getBytes());

            cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);

            Assert.assertTrue(timing.awaitLatch(addedLatch));
            Assert.assertTrue(timing.awaitLatch(initLatch));
            Assert.assertEquals(cache.getCurrentData().size(), 3);
            Assert.assertEquals(cache.getCurrentData().get(0).getData(), "1".getBytes());
            Assert.assertEquals(cache.getCurrentData().get(1).getData(), "2".getBytes());
            Assert.assertEquals(cache.getCurrentData().get(2).getData(), "3".getBytes());
        }
        finally
        {
            Closeables.closeQuietly(cache);
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testUpdateWhenNotCachingData() throws Exception
    {
        Timing timing = new Timing();

        CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(
            1));
        client.start();
        try
        {
            final CountDownLatch updatedLatch = new CountDownLatch(1);
            final CountDownLatch addedLatch = new CountDownLatch(1);
            client.create().creatingParentsIfNeeded().forPath("/test");
            PathChildrenCache cache = new PathChildrenCache(client, "/test", false);
            cache.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    if ( event.getType() == PathChildrenCacheEvent.Type.CHILD_UPDATED )
                    {
                        updatedLatch.countDown();
                    }
                    else if ( event.getType() == PathChildrenCacheEvent.Type.CHILD_ADDED )
                    {
                        addedLatch.countDown();
                    }
                }
            });
            cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);

            client.create().forPath("/test/foo", "first".getBytes());
            Assert.assertTrue(timing.awaitLatch(addedLatch));

            client.setData().forPath("/test/foo", "something new".getBytes());
            Assert.assertTrue(timing.awaitLatch(updatedLatch));
        }
        finally
        {
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testEnsurePath() throws Exception
    {
        Timing timing = new Timing();

        CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            PathChildrenCache cache = new PathChildrenCache(client, "/one/two/three", false);
            cache.start();
            timing.sleepABit();

            try
            {
                client.create().forPath("/one/two/three/four");
            }
            catch ( KeeperException.NoNodeException e )
            {
                Assert.fail("Path should exist", e);
            }
        }
        finally
        {
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testDeleteThenCreate() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            client.create().forPath("/test");
            client.create().forPath("/test/foo", "one".getBytes());

            final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
            client.getUnhandledErrorListenable().addListener(new UnhandledErrorListener()
            {

                @Override
                public void unhandledError(String message, Throwable e)
                {
                    error.set(e);
                }
            });

            final CountDownLatch removedLatch = new CountDownLatch(1);
            final CountDownLatch postRemovedLatch = new CountDownLatch(1);
            final CountDownLatch dataLatch = new CountDownLatch(1);
            PathChildrenCache cache = new PathChildrenCache(client, "/test", true);
            cache.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    if ( event.getType() == PathChildrenCacheEvent.Type.CHILD_REMOVED )
                    {
                        removedLatch.countDown();
                        Assert.assertTrue(postRemovedLatch.await(10, TimeUnit.SECONDS));
                    }
                    else
                    {
                        try
                        {
                            Assert.assertEquals(event.getData().getData(), "two".getBytes());
                        }
                        finally
                        {
                            dataLatch.countDown();
                        }
                    }
                }
            });
            cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);

            client.delete().forPath("/test/foo");
            Assert.assertTrue(removedLatch.await(10, TimeUnit.SECONDS));
            client.create().forPath("/test/foo", "two".getBytes());
            postRemovedLatch.countDown();
            Assert.assertTrue(dataLatch.await(10, TimeUnit.SECONDS));

            Throwable t = error.get();
            if ( t != null )
            {
                Assert.fail("Assert", t);
            }

            cache.close();
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testRebuildAgainstOtherProcesses() throws Exception
    {
        final CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            client.create().forPath("/test");
            client.create().forPath("/test/foo");
            client.create().forPath("/test/bar");
            client.create().forPath("/test/snafu", "original".getBytes());

            final CountDownLatch addedLatch = new CountDownLatch(2);
            final PathChildrenCache cache = new PathChildrenCache(client, "/test", true);
            cache.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    if ( event.getType() == PathChildrenCacheEvent.Type.CHILD_ADDED )
                    {
                        if ( event.getData().getPath().equals("/test/test") )
                        {
                            addedLatch.countDown();
                        }
                    }
                    else if ( event.getType() == PathChildrenCacheEvent.Type.CHILD_UPDATED )
                    {
                        if ( event.getData().getPath().equals("/test/snafu") )
                        {
                            addedLatch.countDown();
                        }
                    }
                }
            });
            cache.rebuildTestExchanger = new Exchanger<Object>();
            ExecutorService service = Executors.newSingleThreadExecutor();
            final AtomicReference<String> deletedPath = new AtomicReference<String>();
            Future<Object> future = service.submit(new Callable<Object>()
            {

                @Override
                public Object call() throws Exception
                {
                    cache.rebuildTestExchanger.exchange(new Object());

                    // simulate another process adding a node while we're rebuilding
                    client.create().forPath("/test/test");

                    List<ChildData> currentData = cache.getCurrentData();
                    Assert.assertTrue(currentData.size() > 0);

                    // simulate another process removing a node while we're rebuilding
                    client.delete().forPath(currentData.get(0).getPath());
                    deletedPath.set(currentData.get(0).getPath());

                    cache.rebuildTestExchanger.exchange(new Object());

                    ChildData childData = null;
                    while ( childData == null )
                    {
                        childData = cache.getCurrentData("/test/snafu");
                        Thread.sleep(1000);
                    }
                    Assert.assertEquals(childData.getData(), "original".getBytes());
                    client.setData().forPath("/test/snafu", "grilled".getBytes());

                    cache.rebuildTestExchanger.exchange(new Object());

                    return null;
                }
            });
            cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
            future.get();

            Assert.assertTrue(addedLatch.await(10, TimeUnit.SECONDS));
            Assert.assertNotNull(cache.getCurrentData("/test/test"));
            Assert.assertNull(cache.getCurrentData(deletedPath.get()));
            Assert.assertEquals(cache.getCurrentData("/test/snafu").getData(), "grilled".getBytes());

            cache.close();
        }
        finally
        {
            client.close();
        }
    }

    // see https://github.com/Netflix/curator/issues/27 - was caused by not comparing old->new data
    @Test
    public void testIssue27() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            client.create().forPath("/base");
            client.create().forPath("/base/a");
            client.create().forPath("/base/b");
            client.create().forPath("/base/c");

            client.getChildren().forPath("/base");

            final List<PathChildrenCacheEvent.Type> events = Lists.newArrayList();
            final Semaphore semaphore = new Semaphore(0);
            PathChildrenCache cache = new PathChildrenCache(client, "/base", true);
            cache.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    events.add(event.getType());
                    semaphore.release();
                }
            });
            cache.start();

            Assert.assertTrue(semaphore.tryAcquire(3, 10, TimeUnit.SECONDS));

            client.delete().forPath("/base/a");
            Assert.assertTrue(semaphore.tryAcquire(1, 10, TimeUnit.SECONDS));

            client.create().forPath("/base/a");
            Assert.assertTrue(semaphore.tryAcquire(1, 10, TimeUnit.SECONDS));

            List<PathChildrenCacheEvent.Type> expected = Lists.newArrayList(
                PathChildrenCacheEvent.Type.CHILD_ADDED,
                PathChildrenCacheEvent.Type.CHILD_ADDED,
                PathChildrenCacheEvent.Type.CHILD_ADDED,
                PathChildrenCacheEvent.Type.CHILD_REMOVED,
                PathChildrenCacheEvent.Type.CHILD_ADDED);
            Assert.assertEquals(expected, events);
        }
        finally
        {
            client.close();
        }
    }

    // test Issue 27 using new rebuild() method
    @Test
    public void testIssue27Alt() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            client.create().forPath("/base");
            client.create().forPath("/base/a");
            client.create().forPath("/base/b");
            client.create().forPath("/base/c");

            client.getChildren().forPath("/base");

            final List<PathChildrenCacheEvent.Type> events = Lists.newArrayList();
            final Semaphore semaphore = new Semaphore(0);
            PathChildrenCache cache = new PathChildrenCache(client, "/base", true);
            cache.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    events.add(event.getType());
                    semaphore.release();
                }
            });
            cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);

            client.delete().forPath("/base/a");
            Assert.assertTrue(semaphore.tryAcquire(1, 10, TimeUnit.SECONDS));

            client.create().forPath("/base/a");
            Assert.assertTrue(semaphore.tryAcquire(1, 10, TimeUnit.SECONDS));

            List<PathChildrenCacheEvent.Type> expected = Lists.newArrayList(
                PathChildrenCacheEvent.Type.CHILD_REMOVED,
                PathChildrenCacheEvent.Type.CHILD_ADDED);
            Assert.assertEquals(expected, events);
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testKilledSession() throws Exception
    {
        Timing timing = new Timing();
        CuratorFramework client = null;
        try
        {
            client = CuratorFrameworkFactory.newClient(
                server.getConnectString(), timing.session(), timing.connection(),
                new RetryOneTime(1));
            client.start();
            client.create().forPath("/test");

            PathChildrenCache cache = new PathChildrenCache(client, "/test", true);
            cache.start();

            final CountDownLatch childAddedLatch = new CountDownLatch(1);
            final CountDownLatch lostLatch = new CountDownLatch(1);
            final CountDownLatch reconnectedLatch = new CountDownLatch(1);
            final CountDownLatch removedLatch = new CountDownLatch(1);
            cache.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    if ( event.getType() == PathChildrenCacheEvent.Type.CHILD_ADDED )
                    {
                        childAddedLatch.countDown();
                    }
                    else if ( event.getType() == PathChildrenCacheEvent.Type.CONNECTION_LOST )
                    {
                        lostLatch.countDown();
                    }
                    else if ( event.getType() == PathChildrenCacheEvent.Type.CONNECTION_RECONNECTED )
                    {
                        reconnectedLatch.countDown();
                    }
                    else if ( event.getType() == PathChildrenCacheEvent.Type.CHILD_REMOVED )
                    {
                        removedLatch.countDown();
                    }
                }
            });

            client.create().withMode(CreateMode.EPHEMERAL).forPath("/test/me", "data".getBytes());
            Assert.assertTrue(timing.awaitLatch(childAddedLatch));

            KillSession.kill(client.getZookeeperClient().getZooKeeper(), server.getConnectString());
            Assert.assertTrue(timing.awaitLatch(lostLatch));
            Assert.assertTrue(timing.awaitLatch(reconnectedLatch));
            Assert.assertTrue(timing.awaitLatch(removedLatch));
        }
        finally
        {
            Closeables.closeQuietly(client);
        }
    }

    @Test
    public void testModes() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            client.create().forPath("/test");

            for ( boolean cacheData : new boolean[]{
                false, true
            } )
            {
                internalTestMode(client, cacheData);

                client.delete().forPath("/test/one");
                client.delete().forPath("/test/two");
            }
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testRebuildNode() throws Exception
    {
        PathChildrenCache cache = null;
        CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            client.create().creatingParentsIfNeeded().forPath("/test/one", "one".getBytes());

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger counter = new AtomicInteger();
            final Semaphore semaphore = new Semaphore(1);
            cache = new PathChildrenCache(client, "/test", true)
            {

                @Override
                void getDataAndStat(String fullPath) throws Exception
                {
                    semaphore.acquire();
                    counter.incrementAndGet();
                    super.getDataAndStat(fullPath);
                    latch.countDown();
                }
            };
            cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);

            latch.await();

            int saveCounter = counter.get();
            client.setData().forPath("/test/one", "alt".getBytes());
            cache.rebuildNode("/test/one");
            Assert.assertEquals(cache.getCurrentData("/test/one").getData(), "alt".getBytes());
            Assert.assertEquals(saveCounter, counter.get());

            semaphore.release(1000);
        }
        finally
        {
            Closeables.closeQuietly(cache);
            Closeables.closeQuietly(client);
        }
    }

    private void internalTestMode(CuratorFramework client, boolean cacheData) throws Exception
    {
        PathChildrenCache cache = new PathChildrenCache(client, "/test", cacheData);

        final CountDownLatch latch = new CountDownLatch(2);
        cache.getListenable().addListener(new PathChildrenCacheListener()
        {

            @Override
            public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                throws Exception
            {
                if ( event.getType() == PathChildrenCacheEvent.Type.CHILD_ADDED )
                {
                    latch.countDown();
                }
            }
        });
        cache.start();

        client.create().forPath("/test/one", "one".getBytes());
        client.create().forPath("/test/two", "two".getBytes());
        Assert.assertTrue(latch.await(10, TimeUnit.SECONDS));

        for ( ChildData data : cache.getCurrentData() )
        {
            if ( cacheData )
            {
                Assert.assertNotNull(data.getData());
                Assert.assertNotNull(data.getStat());
            }
            else
            {
                Assert.assertNull(data.getData());
                Assert.assertNotNull(data.getStat());
            }
        }

        cache.close();
    }

    @Test
    public void testBasics() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            client.create().forPath("/test");

            final BlockingQueue<PathChildrenCacheEvent.Type> events = new LinkedBlockingQueue<PathChildrenCacheEvent.Type>();
            PathChildrenCache cache = new PathChildrenCache(client, "/test", true);
            cache.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    if ( event.getData().getPath().equals("/test/one") )
                    {
                        events.offer(event.getType());
                    }
                }
            });
            cache.start();

            client.create().forPath("/test/one", "hey there".getBytes());
            Assert.assertEquals(
                events.poll(10, TimeUnit.SECONDS), PathChildrenCacheEvent.Type.CHILD_ADDED);

            client.setData().forPath("/test/one", "sup!".getBytes());
            Assert.assertEquals(
                events.poll(10, TimeUnit.SECONDS), PathChildrenCacheEvent.Type.CHILD_UPDATED);
            Assert.assertEquals(new String(cache.getCurrentData("/test/one").getData()), "sup!");

            client.delete().forPath("/test/one");
            Assert.assertEquals(
                events.poll(10, TimeUnit.SECONDS), PathChildrenCacheEvent.Type.CHILD_REMOVED);

            cache.close();
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testBasicsOnTwoCachesWithSameExecutor() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            client.create().forPath("/test");

            final BlockingQueue<PathChildrenCacheEvent.Type> events = new LinkedBlockingQueue<PathChildrenCacheEvent.Type>();
            final ExecutorService exec = Executors.newSingleThreadExecutor();
            PathChildrenCache cache = new PathChildrenCache(client, "/test", true, false, exec);
            cache.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    if ( event.getData().getPath().equals("/test/one") )
                    {
                        events.offer(event.getType());
                    }
                }
            });
            cache.start();

            final BlockingQueue<PathChildrenCacheEvent.Type> events2 = new LinkedBlockingQueue<PathChildrenCacheEvent.Type>();
            PathChildrenCache cache2 = new PathChildrenCache(client, "/test", true, false, exec);
            cache2.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    if ( event.getData().getPath().equals("/test/one") )
                    {
                        events2.offer(event.getType());
                    }
                }
            });
            cache2.start();

            client.create().forPath("/test/one", "hey there".getBytes());
            Assert.assertEquals(
                events.poll(10, TimeUnit.SECONDS), PathChildrenCacheEvent.Type.CHILD_ADDED);
            Assert.assertEquals(
                events2.poll(10, TimeUnit.SECONDS), PathChildrenCacheEvent.Type.CHILD_ADDED);

            client.setData().forPath("/test/one", "sup!".getBytes());
            Assert.assertEquals(
                events.poll(10, TimeUnit.SECONDS), PathChildrenCacheEvent.Type.CHILD_UPDATED);
            Assert.assertEquals(
                events2.poll(10, TimeUnit.SECONDS), PathChildrenCacheEvent.Type.CHILD_UPDATED);
            Assert.assertEquals(new String(cache.getCurrentData("/test/one").getData()), "sup!");
            Assert.assertEquals(new String(cache2.getCurrentData("/test/one").getData()), "sup!");

            client.delete().forPath("/test/one");
            Assert.assertEquals(
                events.poll(10, TimeUnit.SECONDS), PathChildrenCacheEvent.Type.CHILD_REMOVED);
            Assert.assertEquals(
                events2.poll(10, TimeUnit.SECONDS), PathChildrenCacheEvent.Type.CHILD_REMOVED);

            cache.close();
            cache2.close();
        }
        finally
        {
            client.close();
        }
    }

    @Test
    public void testDeleteNodeAfterCloseDoesntCallExecutor() throws Exception
    {
        CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), new RetryOneTime(1));
        client.start();
        try
        {
            client.create().forPath("/test");

            final ExecuteCalledWatchingExecutorService exec = new ExecuteCalledWatchingExecutorService(
                Executors.newSingleThreadExecutor());
            PathChildrenCache cache = new PathChildrenCache(client, "/test", true, false, exec);

            cache.start();
            client.create().forPath("/test/one", "hey there".getBytes());

            cache.rebuild();
            Assert.assertEquals(
                new String(cache.getCurrentData("/test/one").getData()), "hey there");
            Assert.assertTrue(exec.isExecuteCalled());

            exec.setExecuteCalled(false);
            cache.close();
            Assert.assertFalse(exec.isExecuteCalled());

            client.delete().forPath("/test/one");
            Thread.sleep(100);
            Assert.assertFalse(exec.isExecuteCalled());
        }
        finally
        {
            client.close();
        }

    }

    /**
     * Test the case where there is a whole tree but we're only interested in updates of direct
     * descendants
     *
     * @throws Exception
     */
    @Test
    public void testCacheDirectDesendantsOnly() throws Exception
    {
        Timing timing = new Timing();
        PathChildrenCache cache = null;

        CuratorFramework client = CuratorFrameworkFactory.newClient(
            server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(
            1));

        try
        {
            client.start();

            final CountDownLatch initLatch = new CountDownLatch(1);

            final CountDownLatch createLatch = new CountDownLatch(1);
            final CountDownLatch updateLatch = new CountDownLatch(1);
            final CountDownLatch deleteLatch = new CountDownLatch(1);

            final String root = "/cachetest";

            cache = new PathChildrenCache(
                client, root, true, DescendantHandlingMode.DIRECT_DESCENDANTS_ONLY);
            cache.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    switch ( event.getType() )
                    {
                    case INITIALIZED:
                        Assert.assertTrue(initLatch.getCount() >= 1);
                        initLatch.countDown();
                        break;
                    case CHILD_ADDED:
                        Assert.assertTrue(createLatch.getCount() >= 1);
                        createLatch.countDown();
                        break;
                    case CHILD_REMOVED:
                        Assert.assertTrue(deleteLatch.getCount() >= 1);
                        deleteLatch.countDown();
                        break;
                    case CHILD_UPDATED:
                        Assert.assertTrue(updateLatch.getCount() >= 1);
                        updateLatch.countDown();
                        break;
                    }
                }
            });

            cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);
            Assert.assertTrue(timing.awaitLatch(initLatch));

            client.create().creatingParentsIfNeeded().forPath(
                root + "/oneLevel/secondLevel/thirdLevel");
            Assert.assertTrue(timing.awaitLatch(createLatch));
            client.setData().forPath(root + "/oneLevel", "NewData1".getBytes());
            client.setData().forPath(root + "/oneLevel/secondLevel", "NewData2".getBytes());
            client.setData().forPath(
                root + "/oneLevel/secondLevel/thirdLevel", "NewData3".getBytes());
            Assert.assertTrue(timing.awaitLatch(updateLatch));
            client.delete().deletingChildrenIfNeeded().forPath(root + "/oneLevel");
            Assert.assertTrue(timing.awaitLatch(deleteLatch));
        }
        finally
        {
            Closeables.closeQuietly(cache);
            Closeables.closeQuietly(client);
        }
    }

    /**
     * Test the case where there is a whole tree and we're interested in all updates. See CURATOR-33
     *
     * @throws Exception
     */
    @Test
    public void testCacheWholeTree() throws Exception
    {
        Timing timing = new Timing();
        PathChildrenCache cache = null;

        CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
        try
        {
            client.start();

            final CountDownLatch initLatch = new CountDownLatch(1);

            final CountDownLatch createLatch = new CountDownLatch(3);
            final CountDownLatch updateLatch = new CountDownLatch(3);
            final CountDownLatch deleteLatch = new CountDownLatch(3);

            final String root = "/cachetest";

            cache = new PathChildrenCache(client, root, true, DescendantHandlingMode.ALL_DESCENDANTS);
            cache.getListenable().addListener(new PathChildrenCacheListener()
            {

                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
                    throws Exception
                {
                    switch ( event.getType() )
                    {
                    case INITIALIZED:
                        initLatch.countDown();
                        break;
                    case CHILD_ADDED:
                        createLatch.countDown();
                        break;
                    case CHILD_REMOVED:
                        deleteLatch.countDown();
                        break;
                    case CHILD_UPDATED:
                        updateLatch.countDown();
                        break;
                    }
                }
            });

            cache.start(PathChildrenCache.StartMode.POST_INITIALIZED_EVENT);
            Assert.assertTrue(timing.awaitLatch(initLatch));

            client.create().creatingParentsIfNeeded().forPath(root + "/oneLevel/secondLevel/thirdLevel");
            Assert.assertTrue(timing.awaitLatch(createLatch));
            client.setData().forPath(root + "/oneLevel", "NewData1".getBytes());
            client.setData().forPath(root + "/oneLevel/secondLevel", "NewData2".getBytes());
            client.setData().forPath(root + "/oneLevel/secondLevel/thirdLevel", "NewData3".getBytes());
            Assert.assertTrue(timing.awaitLatch(updateLatch));
            client.delete().deletingChildrenIfNeeded().forPath(root + "/oneLevel");
            Assert.assertTrue(timing.awaitLatch(deleteLatch));
        }
        finally
        {
            Closeables.closeQuietly(cache);
            Closeables.closeQuietly(client);
        }
    }

    public static class ExecuteCalledWatchingExecutorService extends DelegatingExecutorService
    {

        boolean executeCalled = false;

        public ExecuteCalledWatchingExecutorService(ExecutorService delegate)
        {
            super(delegate);
        }

        @Override
        public synchronized void execute(Runnable command)
        {
            executeCalled = true;
            super.execute(command);
        }

        public synchronized boolean isExecuteCalled()
        {
            return executeCalled;
        }

        public synchronized void setExecuteCalled(boolean executeCalled)
        {
            this.executeCalled = executeCalled;
        }
    }

    public static class DelegatingExecutorService implements ExecutorService
    {

        private final ExecutorService delegate;

        public DelegatingExecutorService(ExecutorService delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void shutdown()
        {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow()
        {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown()
        {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated()
        {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException
        {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task)
        {
            return delegate.submit(task);
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result)
        {
            return delegate.submit(task, result);
        }

        @Override
        public Future<?> submit(Runnable task)
        {
            return delegate.submit(task);
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException
        {
            return delegate.invokeAll(tasks);
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                             long timeout,
                                             TimeUnit unit) throws InterruptedException
        {
            return delegate.invokeAll(tasks, timeout, unit);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException
        {
            return delegate.invokeAny(tasks);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException
        {
            return delegate.invokeAny(tasks, timeout, unit);
        }

        @Override
        public void execute(Runnable command)
        {
            delegate.execute(command);
        }
    }
}
