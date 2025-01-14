/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.titus.master.mesos;

import java.util.Map;
import java.util.Optional;

import com.netflix.fenzo.PreferentialNamedConsumableResourceSet;
import com.netflix.titus.api.jobmanager.model.job.Job;
import com.netflix.titus.api.jobmanager.model.job.Task;
import com.netflix.titus.master.model.job.TitusQueuableTask;
import org.apache.mesos.Protos;

public interface TaskInfoFactory<T> {
    T newTaskInfo(TitusQueuableTask<Job, Task> fenzoTask,
                  Job<?> job,
                  Task task,
                  String hostname,
                  Map<String, String> attributesMap,
                  Protos.SlaveID slaveID,
                  PreferentialNamedConsumableResourceSet.ConsumeResult consumeResult,
                  Optional<String> executorUriOverrideOpt,
                  Map<String, String> passthroughAttributes);
}
