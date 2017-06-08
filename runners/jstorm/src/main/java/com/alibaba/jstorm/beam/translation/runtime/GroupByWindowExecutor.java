/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.jstorm.beam.translation.runtime;

import java.io.Serializable;
import java.util.List;

import com.alibaba.jstorm.beam.translation.runtime.state.JStormStateInternals;
import com.alibaba.jstorm.beam.translation.runtime.timer.JStormTimerInternals;
import com.google.common.collect.ImmutableList;
import org.apache.beam.runners.core.DoFnRunner;
import org.apache.beam.runners.core.DoFnRunners;
import org.apache.beam.runners.core.ExecutionContext.StepContext;
import org.apache.beam.runners.core.GroupAlsoByWindowViaWindowSetNewDoFn;
import org.apache.beam.runners.core.KeyedWorkItem;
import org.apache.beam.runners.core.KeyedWorkItems;
import org.apache.beam.runners.core.StateInternals;
import org.apache.beam.runners.core.StateInternalsFactory;
import org.apache.beam.runners.core.StateNamespace;
import org.apache.beam.runners.core.StateNamespaces;
import org.apache.beam.runners.core.SystemReduceFn;
import org.apache.beam.runners.core.TimerInternals;
import org.apache.beam.runners.core.TimerInternalsFactory;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.runners.core.NullSideInputReader;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TupleTag;

import com.alibaba.jstorm.beam.StormPipelineOptions;
import com.alibaba.jstorm.beam.translation.TranslationContext;
import com.alibaba.jstorm.beam.translation.TranslationContext.UserGraphContext;
import com.alibaba.jstorm.beam.translation.util.DefaultStepContext;
import com.alibaba.jstorm.beam.util.RunnerUtils;

import static com.google.common.base.Preconditions.checkArgument;

public class GroupByWindowExecutor<K, V> extends DoFnExecutor<KeyedWorkItem<K, V>, KV<K, Iterable<V>>> {
    private static final long serialVersionUID = -7563050475488610553L;

    private class GroupByWindowOutputManager implements DoFnRunners.OutputManager, Serializable {

        @Override
        public <T> void output(TupleTag<T> tag, WindowedValue<T> output) {
            KV kv = (KV) output.getValue();
            KV immutableKv = kv.getValue() instanceof Iterable ?
                    KV.of(kv.getKey(), ImmutableList.copyOf((Iterable) kv.getValue())) :
                    kv;
            executorsBolt.processExecutorElem(tag, (WindowedValue<T>) output.withValue(immutableKv));
        }
    }

    private KvCoder<K, V> inputKvCoder;
    private SystemReduceFn<K, V, Iterable<V>, Iterable<V>, BoundedWindow> reduceFn;

    public GroupByWindowExecutor(
            String stepName,
            String description,
            TranslationContext context,
            StormPipelineOptions pipelineOptions,
            WindowingStrategy<?, ?> windowingStrategy,
            TupleTag<KV<K, Iterable<V>>> mainTupleTag, List<TupleTag<?>> sideOutputTags) {
        // The doFn will be created when runtime. Just pass "null" here
        super(stepName, description, pipelineOptions, null, null, windowingStrategy, null, null, null, mainTupleTag, sideOutputTags);

        this.outputManager = new GroupByWindowOutputManager();
        UserGraphContext userGraphContext = context.getUserGraphContext();
        PCollection<KV<K, V>> input = (PCollection<KV<K, V>>) userGraphContext.getInput();
        this.inputKvCoder = (KvCoder<K, V>) input.getCoder();
    }

    private DoFn<KeyedWorkItem<K, V>, KV<K, Iterable<V>>> getGroupByWindowDoFn() {
        final StateInternalsFactory<K> stateFactory = new StateInternalsFactory<K>() {
            @Override
            public StateInternals stateInternalsForKey(K key) {
                return new JStormStateInternals<K>(key, kvStoreManager, executorsBolt.timerService());
            }
        };
        TimerInternalsFactory<K> timerFactory = new TimerInternalsFactory<K>() {
            @Override
            public TimerInternals timerInternalsForKey(K key) {
                return new JStormTimerInternals<>(key, GroupByWindowExecutor.this, executorContext.getExecutorsBolt().timerService());
            }
        };

        reduceFn = SystemReduceFn.buffering(inputKvCoder.getValueCoder());
        DoFn<KeyedWorkItem<K, V>, KV<K, Iterable<V>>> doFn =
              GroupAlsoByWindowViaWindowSetNewDoFn.create(
                  windowingStrategy, stateFactory, timerFactory, NullSideInputReader.empty(),
                      (SystemReduceFn) reduceFn, outputManager, mainTupleTag);
        return doFn;
    }

    @Override
    protected DoFnRunner<KeyedWorkItem<K, V>, KV<K, Iterable<V>>> getDoFnRunner() {
        doFn = getGroupByWindowDoFn();

        DoFnRunner<KeyedWorkItem<K, V>, KV<K, Iterable<V>>> simpleRunner = DoFnRunners.<KeyedWorkItem<K, V>, KV<K, Iterable<V>>>simpleRunner(
                this.pipelineOptions,
                this.doFn,
                NullSideInputReader.empty(),
                this.outputManager,
                this.mainTupleTag,
                this.sideOutputTags,
                this.stepContext,
                this.windowingStrategy);

        DoFnRunner<KeyedWorkItem<K, V>, KV<K, Iterable<V>>> doFnRunner = DoFnRunners.lateDataDroppingRunner(
                simpleRunner,
                this.stepContext,
                this.windowingStrategy);
        return new DoFnRunnerWithMetrics<>(
            stepName, doFnRunner, MetricsReporter.create(metricClient));
    }

    @Override
    public void process(TupleTag tag, WindowedValue elem) {
        /**
         *  For GroupByKey, KV type elem is received. We need to convert the KV elem
         *  into KeyedWorkItem first, which is the expected type in LateDataDroppingDoFnRunner.
         */
        KeyedWorkItem<K, V> keyedWorkItem = RunnerUtils.toKeyedWorkItem((WindowedValue<KV<K, V>>) elem);
        runner.processElement(elem.withValue(keyedWorkItem));
    }

    @Override
    public void onTimer(Object key, TimerInternals.TimerData timerData) {
        StateNamespace namespace = timerData.getNamespace();
        checkArgument(namespace instanceof StateNamespaces.WindowNamespace);

        runner.processElement(
                WindowedValue.valueInGlobalWindow(
                        KeyedWorkItems.<K, V>timersWorkItem((K) key, ImmutableList.of(timerData))));
    }

    @Override
    public String toString() {
        return super.toString();
    }
}
