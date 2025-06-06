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

package org.apache.flink.state.changelog;

import org.apache.flink.annotation.Internal;
import org.apache.flink.core.execution.SavepointFormatType;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.state.AbstractKeyedStateBackend;
import org.apache.flink.runtime.state.CheckpointableKeyedStateBackend;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.state.KeyedStateBackendParametersImpl;
import org.apache.flink.runtime.state.KeyedStateHandle;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.runtime.state.changelog.ChangelogStateBackendHandle;
import org.apache.flink.runtime.state.changelog.ChangelogStateBackendHandle.ChangelogStateBackendHandleImpl;
import org.apache.flink.runtime.state.delegate.DelegatingStateBackend;
import org.apache.flink.runtime.state.ttl.TtlTimeProvider;
import org.apache.flink.state.changelog.restore.ChangelogBackendRestoreOperation.BaseBackendBuilder;
import org.apache.flink.util.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An abstract base implementation of the {@link DelegatingStateBackend} interface whose subclasses
 * use delegatedStateBackend and State changes to restore.
 */
@Internal
public abstract class AbstractChangelogStateBackend implements DelegatingStateBackend {

    private static final long serialVersionUID = 1000L;

    private static final Logger LOG = LoggerFactory.getLogger(AbstractChangelogStateBackend.class);

    protected final StateBackend delegatedStateBackend;

    /**
     * Delegate a state backend by a ChangelogStateBackend.
     *
     * <p>As FLINK-22678 mentioned, we currently hide this constructor from user.
     *
     * @param stateBackend the delegated state backend.
     */
    AbstractChangelogStateBackend(StateBackend stateBackend) {
        this.delegatedStateBackend = Preconditions.checkNotNull(stateBackend);

        Preconditions.checkArgument(
                !(stateBackend instanceof DelegatingStateBackend),
                "Recursive Delegation is not supported.");

        LOG.info(
                "ChangelogStateBackend is used, delegating {}.",
                delegatedStateBackend.getClass().getSimpleName());
    }

    @Override
    public <K> CheckpointableKeyedStateBackend<K> createKeyedStateBackend(
            KeyedStateBackendParameters<K> parameters) throws Exception {
        return restore(
                parameters.getEnv(),
                parameters.getOperatorIdentifier(),
                parameters.getKeyGroupRange(),
                parameters.getTtlTimeProvider(),
                parameters.getMetricGroup(),
                castHandles(parameters.getStateHandles()),
                baseHandles ->
                        (AbstractKeyedStateBackend<K>)
                                delegatedStateBackend.createKeyedStateBackend(
                                        new KeyedStateBackendParametersImpl(parameters)
                                                .setStateHandles(baseHandles)));
    }

    @Override
    public OperatorStateBackend createOperatorStateBackend(
            OperatorStateBackendParameters parameters) throws Exception {
        return delegatedStateBackend.createOperatorStateBackend(parameters);
    }

    @Override
    public boolean useManagedMemory() {
        return delegatedStateBackend.useManagedMemory();
    }

    @Override
    public StateBackend getDelegatedStateBackend() {
        return delegatedStateBackend;
    }

    @Override
    public boolean supportsSavepointFormat(SavepointFormatType formatType) {
        return delegatedStateBackend.supportsSavepointFormat(formatType);
    }

    protected abstract <K> CheckpointableKeyedStateBackend<K> restore(
            Environment env,
            String operatorIdentifier,
            KeyGroupRange keyGroupRange,
            TtlTimeProvider ttlTimeProvider,
            MetricGroup metricGroup,
            Collection<ChangelogStateBackendHandle> stateBackendHandles,
            BaseBackendBuilder<K> baseBackendBuilder)
            throws Exception;

    private Collection<ChangelogStateBackendHandle> castHandles(
            Collection<KeyedStateHandle> stateHandles) {
        if (stateHandles.stream().anyMatch(h -> !(h instanceof ChangelogStateBackendHandle))) {
            LOG.warn("Some state handles do not contain changelog: {}.", stateHandles);
        }
        return stateHandles.stream()
                .filter(Objects::nonNull)
                .map(ChangelogStateBackendHandleImpl::getChangelogStateBackendHandle)
                .collect(Collectors.toList());
    }
}
