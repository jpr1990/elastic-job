/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
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
 * </p>
 */

package com.dangdang.ddframe.job.cloud.scheduler.state.ready;

import com.dangdang.ddframe.job.cloud.scheduler.boot.env.BootstrapEnvironment;
import com.dangdang.ddframe.job.cloud.scheduler.config.CloudJobConfiguration;
import com.dangdang.ddframe.job.cloud.scheduler.config.ConfigurationService;
import com.dangdang.ddframe.job.cloud.scheduler.config.JobExecutionType;
import com.dangdang.ddframe.job.cloud.scheduler.context.ExecutionType;
import com.dangdang.ddframe.job.cloud.scheduler.context.JobContext;
import com.dangdang.ddframe.job.cloud.scheduler.state.misfired.MisfiredService;
import com.dangdang.ddframe.job.cloud.scheduler.state.running.RunningService;
import com.dangdang.ddframe.job.reg.base.CoordinatorRegistryCenter;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Collections2;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 待运行作业队列服务.
 *
 * @author zhangliang
 */
@Slf4j
public class ReadyService {
    
    private final BootstrapEnvironment env = BootstrapEnvironment.getInstance();
    
    private final CoordinatorRegistryCenter regCenter;
    
    private final ConfigurationService configService;
    
    private final RunningService runningService;
    
    private final MisfiredService misfiredService;
    
    public ReadyService(final CoordinatorRegistryCenter regCenter) {
        this.regCenter = regCenter;
        configService = new ConfigurationService(regCenter);
        runningService = new RunningService();
        misfiredService = new MisfiredService(regCenter);
    }
    
    /**
     * 将瞬时作业放入待执行队列.
     * 
     * @param jobName 作业名称
     */
    public void addTransient(final String jobName) {
        if (regCenter.getNumChildren(ReadyNode.ROOT) > env.getFrameworkConfiguration().getJobStateQueueSize()) {
            log.warn("Cannot add transient job, caused by read state queue size is larger than {}.", env.getFrameworkConfiguration().getJobStateQueueSize());
            return;
        }
        Optional<CloudJobConfiguration> cloudJobConfig = configService.load(jobName);
        if (!cloudJobConfig.isPresent() || JobExecutionType.TRANSIENT != cloudJobConfig.get().getJobExecutionType()) {
            return;
        }
        String readyJobNode = ReadyNode.getReadyJobNodePath(jobName);
        String times = regCenter.getDirectly(readyJobNode);
        regCenter.persist(readyJobNode, Integer.toString(null == times ? 1 : Integer.parseInt(times) + 1));
    }
    
    /**
     * 将常驻作业放入待执行队列.
     *
     * @param jobName 作业名称
     */
    public void addDaemon(final String jobName) {
        if (regCenter.getNumChildren(ReadyNode.ROOT) > env.getFrameworkConfiguration().getJobStateQueueSize()) {
            log.warn("Cannot add daemon job, caused by read state queue size is larger than {}.", env.getFrameworkConfiguration().getJobStateQueueSize());
            return;
        }
        Optional<CloudJobConfiguration> cloudJobConfig = configService.load(jobName);
        if (!cloudJobConfig.isPresent() || JobExecutionType.DAEMON != cloudJobConfig.get().getJobExecutionType()) {
            return;
        }
        regCenter.persist(ReadyNode.getReadyJobNodePath(jobName), "1");
    }
    
    /**
     * 从待执行队列中获取所有有资格执行的作业上下文.
     *
     * @param ineligibleJobContexts 无资格执行的作业上下文
     * @return 有资格执行的作业上下文集合
     */
    public Collection<JobContext> getAllEligibleJobContexts(final Collection<JobContext> ineligibleJobContexts) {
        if (!regCenter.isExisted(ReadyNode.ROOT)) {
            return Collections.emptyList();
        }
        Collection<String> ineligibleJobNames = Collections2.transform(ineligibleJobContexts, new Function<JobContext, String>() {
            
            @Override
            public String apply(final JobContext input) {
                return input.getJobConfig().getJobName();
            }
        });
        List<String> jobNames = regCenter.getChildrenKeys(ReadyNode.ROOT);
        List<JobContext> result = new ArrayList<>(jobNames.size());
        for (String each : jobNames) {
            if (ineligibleJobNames.contains(each)) {
                continue;
            }
            Optional<CloudJobConfiguration> jobConfig = configService.load(each);
            if (!jobConfig.isPresent()) {
                regCenter.remove(ReadyNode.getReadyJobNodePath(each));
                continue;
            }
            if (runningService.isJobRunning(each)) {
                if (jobConfig.get().getTypeConfig().getCoreConfig().isMisfire()) {
                    misfiredService.add(each);
                }
                if (JobExecutionType.DAEMON == jobConfig.get().getJobExecutionType()) {
                    result.add(JobContext.from(jobConfig.get(), ExecutionType.READY));
                }
                continue;
            }
            result.add(JobContext.from(jobConfig.get(), ExecutionType.READY));
        }
        return result;
    }
    
    /**
     * 从待执行队列中删除相关作业.
     *
     * @param jobNames 待删除的作业名集合
     */
    public void remove(final Collection<String> jobNames) {
        for (String each : jobNames) {
            String readyJobNode = ReadyNode.getReadyJobNodePath(each);
            String timesStr = regCenter.getDirectly(readyJobNode);
            int times = null == timesStr ? 0 : Integer.parseInt(timesStr);
            if (times <= 1) {
                regCenter.remove(readyJobNode);
            } else {
                regCenter.persist(readyJobNode, Integer.toString(times - 1));
            }
        }
    }
}
