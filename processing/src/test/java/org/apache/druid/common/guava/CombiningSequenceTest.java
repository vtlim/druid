/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.common.guava;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.guava.ExplodingSequence;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.java.util.common.guava.Yielder;
import org.apache.druid.java.util.common.guava.YieldingAccumulator;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Test;
import org.junit.internal.matchers.ThrowableMessageMatcher;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(Parameterized.class)
public class CombiningSequenceTest
{
  @Parameterized.Parameters
  public static Collection<Object[]> valuesToTry()
  {
    return Arrays.asList(new Object[][]{
        {1}, {2}, {3}, {4}, {5}, {1000}
    });
  }

  private final int yieldEvery;

  public CombiningSequenceTest(int yieldEvery)
  {
    this.yieldEvery = yieldEvery;
  }

  @Test
  public void testMerge() throws Exception
  {
    List<Pair<Integer, Integer>> pairs = Arrays.asList(
        Pair.of(0, 1),
        Pair.of(0, 2),
        Pair.of(0, 3),
        Pair.of(1, 1),
        Pair.of(2, 1),
        Pair.of(5, 1),
        Pair.of(5, 10),
        Pair.of(6, 1),
        Pair.of(5, 1)
    );
    List<Pair<Integer, Integer>> expected = Arrays.asList(
        Pair.of(0, 6),
        Pair.of(1, 1),
        Pair.of(2, 1),
        Pair.of(5, 11),
        Pair.of(6, 1),
        Pair.of(5, 1)
    );

    testCombining(pairs, expected);
  }

  @Test
  public void testNoMergeOne() throws Exception
  {
    List<Pair<Integer, Integer>> pairs = Collections.singletonList(
        Pair.of(0, 1)
    );

    List<Pair<Integer, Integer>> expected = Collections.singletonList(
        Pair.of(0, 1)
    );

    testCombining(pairs, expected);
  }

  @Test
  public void testMergeMany() throws Exception
  {
    List<Pair<Integer, Integer>> pairs = Arrays.asList(
        Pair.of(0, 6),
        Pair.of(1, 1),
        Pair.of(2, 1),
        Pair.of(5, 11),
        Pair.of(6, 1),
        Pair.of(5, 1)
    );

    List<Pair<Integer, Integer>> expected = Arrays.asList(
        Pair.of(0, 6),
        Pair.of(1, 1),
        Pair.of(2, 1),
        Pair.of(5, 11),
        Pair.of(6, 1),
        Pair.of(5, 1)
    );

    testCombining(pairs, expected);
  }

  @Test
  public void testNoMergeTwo() throws Exception
  {
    List<Pair<Integer, Integer>> pairs = Arrays.asList(
        Pair.of(0, 1),
        Pair.of(1, 1)
    );

    List<Pair<Integer, Integer>> expected = Arrays.asList(
        Pair.of(0, 1),
        Pair.of(1, 1)
    );

    testCombining(pairs, expected);
  }

  @Test
  public void testMergeTwo() throws Exception
  {
    List<Pair<Integer, Integer>> pairs = Arrays.asList(
        Pair.of(0, 1),
        Pair.of(0, 1)
    );

    List<Pair<Integer, Integer>> expected = Collections.singletonList(
        Pair.of(0, 2)
    );

    testCombining(pairs, expected);
  }

  @Test
  public void testMergeSomeThingsMergedAtEnd() throws Exception
  {
    List<Pair<Integer, Integer>> pairs = Arrays.asList(
        Pair.of(0, 1),
        Pair.of(0, 2),
        Pair.of(0, 3),
        Pair.of(1, 1),
        Pair.of(2, 1),
        Pair.of(5, 1),
        Pair.of(5, 10),
        Pair.of(6, 1),
        Pair.of(5, 1),
        Pair.of(5, 2),
        Pair.of(5, 2),
        Pair.of(5, 2),
        Pair.of(5, 2),
        Pair.of(5, 2)
    );
    List<Pair<Integer, Integer>> expected = Arrays.asList(
        Pair.of(0, 6),
        Pair.of(1, 1),
        Pair.of(2, 1),
        Pair.of(5, 11),
        Pair.of(6, 1),
        Pair.of(5, 11)
    );

    testCombining(pairs, expected);
  }

  @Test
  public void testNothing() throws Exception
  {
    testCombining(Collections.emptyList(), Collections.emptyList());
  }

  @Test
  public void testExplodingSequence()
  {
    final ExplodingSequence<Integer> bomb =
        new ExplodingSequence<>(Sequences.simple(ImmutableList.of(1, 2, 2)), false, true);

    final CombiningSequence<Integer> combiningSequence =
        CombiningSequence.create(bomb, Comparator.naturalOrder(), (a, b) -> a);

    try {
      combiningSequence.toYielder(
          null,
          new YieldingAccumulator<Integer, Integer>()
          {
            @Override
            public Integer accumulate(Integer accumulated, Integer in)
            {
              if (in > 1) {
                throw new RuntimeException("boom");
              }

              return in;
            }
          }
      );
      Assert.fail("Expected exception");
    }
    catch (Exception e) {
      Assert.assertThat(e, ThrowableMessageMatcher.hasMessage(CoreMatchers.equalTo("boom")));
    }

    Assert.assertEquals("Closes resources", 1, bomb.getCloseCount());
  }

  private void testCombining(List<Pair<Integer, Integer>> pairs, List<Pair<Integer, Integer>> expected)
      throws Exception
  {
    for (int limit = 0; limit < expected.size() + 1; limit++) {
      // limit = 0 doesn't work properly; it returns 1 element
      final int expectedLimit = limit == 0 ? 1 : limit;

      testCombining(
          pairs,
          Lists.newArrayList(Iterables.limit(expected, expectedLimit)),
          limit
      );
    }
  }

  private void testCombining(
      List<Pair<Integer, Integer>> pairs,
      List<Pair<Integer, Integer>> expected,
      int limit
  ) throws Exception
  {
    final String prefix = StringUtils.format("yieldEvery[%d], limit[%d]", yieldEvery, limit);

    // Test that closing works too
    final CountDownLatch closed = new CountDownLatch(1);
    final Closeable closeable = closed::countDown;

    Sequence<Pair<Integer, Integer>> seq = CombiningSequence.create(
        Sequences.simple(pairs).withBaggage(closeable),
        Ordering.natural().onResultOf(p -> p.lhs),
        (lhs, rhs) -> {
          if (lhs == null) {
            return rhs;
          }

          if (rhs == null) {
            return lhs;
          }

          return Pair.of(lhs.lhs, lhs.rhs + rhs.rhs);
        }
    ).limit(limit);

    List<Pair<Integer, Integer>> merged = seq.toList();

    Assert.assertEquals(prefix, expected, merged);

    Yielder<Pair<Integer, Integer>> yielder = seq.toYielder(
        null,
        new YieldingAccumulator<>()
        {
          int count = 0;

          @Override
          public Pair<Integer, Integer> accumulate(
              Pair<Integer, Integer> lhs, Pair<Integer, Integer> rhs
          )
          {
            count++;
            if (count % yieldEvery == 0) {
              this.yield();
            }
            return rhs;
          }
        }
    );

    Iterator<Pair<Integer, Integer>> expectedVals = Iterators.filter(
        expected.iterator(),
        new Predicate<>()
        {
          int count = 0;

          @Override
          public boolean apply(
              @Nullable Pair<Integer, Integer> input
          )
          {
            count++;
            if (count % yieldEvery == 0) {
              return true;
            }
            return false;
          }
        }
    );

    int i = 0;
    if (expectedVals.hasNext()) {
      while (!yielder.isDone()) {
        final Pair<Integer, Integer> expectedVal = expectedVals.next();
        final Pair<Integer, Integer> actual = yielder.get();
        Assert.assertEquals(StringUtils.format("%s, i[%s]", prefix, i++), expectedVal, actual);
        yielder = yielder.next(actual);
      }
    }
    Assert.assertTrue(prefix, yielder.isDone());
    Assert.assertFalse(prefix, expectedVals.hasNext());
    yielder.close();

    Assert.assertTrue("resource closed", closed.await(10000, TimeUnit.MILLISECONDS));
  }
}
