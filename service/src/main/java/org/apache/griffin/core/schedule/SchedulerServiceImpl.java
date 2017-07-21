/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

package org.apache.griffin.core.schedule;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang.StringUtils;
import org.apache.griffin.core.common.JsonConvert;
import org.apache.griffin.core.common.PropertiesOperate;
import org.apache.griffin.core.schedule.entity.JobHealth;
import org.apache.griffin.core.schedule.entity.JobInstance;
import org.apache.griffin.core.schedule.repo.JobInstanceRepo;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.JobKey.jobKey;
import static org.quartz.TriggerBuilder.newTrigger;
import static org.quartz.TriggerKey.triggerKey;

@Service
public class SchedulerServiceImpl implements SchedulerService{

    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerServiceImpl.class);

    @Autowired
    private SchedulerFactoryBean factory;
    @Autowired
    private JobInstanceRepo jobInstanceRepo;

    public static final String ACCURACY_BATCH_GROUP = "BA";

    Properties sparkJobProps=null;


    public SchedulerServiceImpl(){
        try {
            sparkJobProps= PropertiesOperate.getProperties("sparkJob.properties");
        } catch (IOException e) {
            LOGGER.warn("get sparkJob.properties failed! "+e);
        }
    }

    @Override
    public List<Map<String, Serializable>> getJobs() throws SchedulerException {
        Scheduler scheduler = factory.getObject();
        List<Map<String, Serializable>> list = new ArrayList<>();
        for (String groupName : scheduler.getJobGroupNames()) {
            for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(groupName))) {
                setJobsByKey(list,scheduler, jobKey);
            }
        }
        return list;
    }

    public void setJobsByKey(List<Map<String, Serializable>> list,Scheduler scheduler, JobKey jobKey) throws SchedulerException {
        List<Trigger> triggers = (List<Trigger>) scheduler.getTriggersOfJob(jobKey);
        if (triggers.size() > 0) {
            //always get next run
            Map<String, Serializable> map = new HashMap<>();
            setMap(map,scheduler,jobKey);
            list.add(map);
        }
    }


    public void setMap(Map<String, Serializable> map,Scheduler scheduler,JobKey jobKey) throws SchedulerException {
        List<Trigger> triggers = (List<Trigger>) scheduler.getTriggersOfJob(jobKey);
        JobDetail jd = scheduler.getJobDetail(jobKey);
        Date nextFireTime = triggers.get(0).getNextFireTime();
        Date previousFireTime=triggers.get(0).getPreviousFireTime();
        Trigger.TriggerState triggerState=scheduler.getTriggerState(triggers.get(0).getKey());

        map.put("jobName", jobKey.getName());
        map.put("groupName", jobKey.getGroup());
        if (nextFireTime!=null){
            map.put("nextFireTime", nextFireTime.getTime());
        }
        else {
            map.put("nextFireTime", -1);
        }
        if (previousFireTime!=null) {
            map.put("previousFireTime", previousFireTime.getTime());
        }
        else {
            map.put("previousFireTime", -1);
        }
        map.put("triggerState",triggerState);
        map.put("measure", jd.getJobDataMap().getString("measure"));
        map.put("sourcePat",jd.getJobDataMap().getString("sourcePat"));
        map.put("targetPat",jd.getJobDataMap().getString("targetPat"));
        if(StringUtils.isNotEmpty(jd.getJobDataMap().getString("dataStartTimestamp"))) {
            map.put("dataStartTimestamp", jd.getJobDataMap().getString("dataStartTimestamp"));
        }
        map.put("jobStartTime",jd.getJobDataMap().getString("jobStartTime"));
        map.put("periodTime",jd.getJobDataMap().getString("periodTime"));
    }

    @Override
    public Boolean addJob(String groupName, String jobName, String measureName, SchedulerRequestBody schedulerRequestBody) {
        int periodTime = 0;
        Date jobStartTime=null;
//        SimpleDateFormat format=new SimpleDateFormat("yyyyMMdd HH:mm:ss");
        try{
            periodTime = Integer.parseInt(schedulerRequestBody.getPeriodTime());
            jobStartTime=new Date(Long.parseLong(schedulerRequestBody.getJobStartTime()));
            setJobStartTime(jobStartTime,periodTime);
        }catch (Exception e){
            LOGGER.info("jobStartTime or periodTime format error! "+e);
            return false;
        }
        try {
            Scheduler scheduler = factory.getObject();
            TriggerKey triggerKey = triggerKey(jobName, groupName);
            if (scheduler.checkExists(triggerKey)) {
                scheduler.unscheduleJob(triggerKey);
            }
            JobKey jobKey = jobKey(jobName, groupName);
            JobDetail jobDetail;
            if (scheduler.checkExists(jobKey)) {
                jobDetail = scheduler.getJobDetail(jobKey);
                setJobDetail(jobDetail,schedulerRequestBody,measureName,groupName,jobName);
                scheduler.addJob(jobDetail, true);
            } else {
                jobDetail = newJob(SparkSubmitJob.class)
                        .storeDurably()
                        .withIdentity(jobKey)
                        .build();
                //set JobDetail
                setJobDetail(jobDetail,schedulerRequestBody,measureName,groupName,jobName);
                scheduler.addJob(jobDetail, false);
            }
            Trigger trigger = newTrigger()
                    .withIdentity(triggerKey)
                    .forJob(jobDetail)
//					.withSchedule(CronScheduleBuilder.cronSchedule("0 0/1 0 * * ?"))
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withIntervalInSeconds(periodTime)
                            .repeatForever())
                    .startAt(jobStartTime)
                    .build();
            scheduler.scheduleJob(trigger);
            return true;
        } catch (SchedulerException e) {
            LOGGER.error("", e);
            return false;
        }
    }

    public void setJobStartTime(Date jobStartTime,int periodTime){
        long currentTimestamp=System.currentTimeMillis();
        long jobstartTimestamp=jobStartTime.getTime();
        //if jobStartTime is before currentTimestamp, set it as the latest trigger time in the future
        if(jobStartTime.before(new Date(currentTimestamp))){
            long n=(currentTimestamp-jobstartTimestamp)/(long)(periodTime*1000);
            jobstartTimestamp=jobstartTimestamp+(n+1)*(long)(periodTime*1000);
            jobStartTime.setTime(jobstartTimestamp);
        }
    }

    public void setJobDetail(JobDetail jobDetail,SchedulerRequestBody schedulerRequestBody,String measureName,String groupName,String jobName){
        jobDetail.getJobDataMap().put("measure", measureName);
        jobDetail.getJobDataMap().put("sourcePat", schedulerRequestBody.getSourcePat());
        jobDetail.getJobDataMap().put("targetPat", schedulerRequestBody.getTargetPat());
        jobDetail.getJobDataMap().put("dataStartTimestamp", schedulerRequestBody.getDataStartTimestamp());
        jobDetail.getJobDataMap().put("jobStartTime", schedulerRequestBody.getJobStartTime());
        jobDetail.getJobDataMap().put("periodTime", schedulerRequestBody.getPeriodTime());
        jobDetail.getJobDataMap().put("lastTime", "");
        jobDetail.getJobDataMap().put("groupName",groupName);
        jobDetail.getJobDataMap().put("jobName",jobName);
    }

    @Override
    public Boolean deleteJob(String group, String name) {
        try {
            Scheduler scheduler = factory.getObject();
            scheduler.deleteJob(new JobKey(name, group));
            jobInstanceRepo.deleteInGroupAndjobName(group,name);
            return true;
        } catch (SchedulerException e) {
            LOGGER.error(e.getMessage());
            return false;
        }
    }

    @Override
    public List<JobInstance> findInstancesOfJob(String group, String jobName, int page, int size) {
        //update job instances, if updating is failed, it shouldn't block search database
        try{
            updateInstances(group,jobName);
        }catch (IOException e) {
            LOGGER.error("update instances of job "+jobName+" failed. "+e);
        }
        //query and return instances
        Pageable pageRequest=new PageRequest(page,size, Sort.Direction.DESC,"timestamp");
        return jobInstanceRepo.findByGroupNameAndJobName(group,jobName,pageRequest);
    }

    public synchronized void updateInstances(String group, String jobName) throws IOException {
        //update all instance info belongs to this group and job.
        List<JobInstance> jobInstanceList=jobInstanceRepo.findByGroupNameAndJobName(group,jobName);
        for (JobInstance jobInstance:jobInstanceList){
            if (jobInstance.getState().equals("success") || jobInstance.getState().equals("unknown")){
                continue;
            }
            String uri=sparkJobProps.getProperty("sparkJob.uri")+"/"+jobInstance.getSessionId();
            RestTemplate restTemplate=new RestTemplate();
            String resultStr=null;
            try{
                resultStr=restTemplate.getForObject(uri,String.class);
            }catch (Exception e){
                LOGGER.error("spark session "+jobInstance.getSessionId()+" has overdue, set state as unknown!\n"+e);
                jobInstance.setState("unknown");
            }
            TypeReference<HashMap<String,Object>> type=new TypeReference<HashMap<String,Object>>(){};
            HashMap<String,Object> resultMap= JsonConvert.toEntity(resultStr,type);
            try{
                if (resultMap!=null && resultMap.size()!=0){
                    jobInstance.setState(resultMap.get("state").toString());
                    jobInstance.setAppId(resultMap.get("appId").toString());
                }
            }catch (Exception e){
                LOGGER.warn(group+","+jobName+"job Instance has some null field (state or appId). "+e);
                continue;
            }
            jobInstanceRepo.setFixedStateAndappIdFor(jobInstance.getId(),jobInstance.getState(),jobInstance.getAppId());
        }
    }

    @Override
    @Scheduled(fixedDelayString = "${jobInstance.fixedDelay.in.milliseconds}")
    public void startUpdateInstances(){
        List<Object> groupJobList=jobInstanceRepo.findGroupWithJobName();
        for (Object groupJobObj : groupJobList){
            try{
                Object[] groupJob=(Object[])groupJobObj;
                if (groupJob!=null && groupJob.length==2){
                    updateInstances(groupJob[0].toString(),groupJob[1].toString());
                }
            }catch (Exception e){
                LOGGER.error(""+e);
            }
        }
    }

    @Override
    public JobHealth getHealthInfo() throws SchedulerException {
        Scheduler scheduler=factory.getObject();
        int jobCount=scheduler.getJobGroupNames().size();
        int health=0;
        int invalid=0;
        for (String groupName : scheduler.getJobGroupNames()){
            for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(groupName))){
                String jobName=jobKey.getName();
                String jobGroup=jobKey.getGroup();
                Pageable pageRequest=new PageRequest(0,1, Sort.Direction.DESC,"timestamp");
                JobInstance jobInstance=new JobInstance();
                if (jobInstanceRepo.findByGroupNameAndJobName(jobGroup,jobName,pageRequest)!=null
                        &&jobInstanceRepo.findByGroupNameAndJobName(jobGroup,jobName,pageRequest).size()>0){
                    jobInstance=jobInstanceRepo.findByGroupNameAndJobName(jobGroup,jobName,pageRequest).get(0);
                    if(jobInstance.getState().equals("starting")){
                        health++;
                    }else{
                        invalid++;
                    }
                }
            }
        }
        JobHealth jobHealth=new JobHealth(health,invalid,jobCount);
        return jobHealth;
    }
}