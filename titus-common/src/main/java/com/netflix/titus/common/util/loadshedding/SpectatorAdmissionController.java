/*
 * Copyright 2019 Netflix, Inc.
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

package com.netflix.titus.common.util.loadshedding;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.netflix.spectator.api.Registry;
import com.netflix.titus.common.runtime.TitusRuntime;

@Singleton
public class SpectatorAdmissionController implements AdmissionController {

    private static final String METRIC_NAME = "titus.admissionController.decision";

    private final AdmissionController delegate;
    private final Registry registry;

    @Inject
    public SpectatorAdmissionController(AdmissionController delegate, TitusRuntime titusRuntime) {
        this.delegate = delegate;
        this.registry = titusRuntime.getRegistry();
    }

    @Override
    public AdmissionControllerResponse apply(AdmissionControllerRequest request) {
        try {
            AdmissionControllerResponse result = delegate.apply(request);

            registry.counter(METRIC_NAME,
                    "callerId", request.getCallerId(),
                    "endpointName", request.getEndpointName(),
                    "allowed", "" + result.isAllowed(),
                    "decisionPoint", result.getDecisionPoint(),
                    "equivalenceGroup", result.getEquivalenceGroup()
            ).increment();

            return result;
        } catch (Exception e) {
            registry.counter(METRIC_NAME,
                    "callerId", request.getCallerId(),
                    "endpointName", request.getEndpointName(),
                    "error", e.getClass().getSimpleName()
            ).increment();

            throw e;
        }
    }
}
