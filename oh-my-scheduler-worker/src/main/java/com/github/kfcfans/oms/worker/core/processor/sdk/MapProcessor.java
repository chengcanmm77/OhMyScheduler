package com.github.kfcfans.oms.worker.core.processor.sdk;

import akka.actor.ActorSelection;
import akka.pattern.Patterns;
import com.github.kfcfans.oms.common.RemoteConstant;
import com.github.kfcfans.oms.common.response.AskResponse;
import com.github.kfcfans.oms.worker.OhMyWorker;
import com.github.kfcfans.oms.worker.common.ThreadLocalStore;
import com.github.kfcfans.oms.worker.common.constants.TaskConstant;
import com.github.kfcfans.oms.worker.common.utils.AkkaUtils;
import com.github.kfcfans.oms.worker.core.processor.ProcessResult;
import com.github.kfcfans.oms.worker.persistence.TaskDO;
import com.github.kfcfans.oms.worker.pojo.request.ProcessorMapTaskRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Map 处理器，允许开发者自定义拆分任务进行分布式执行
 *
 * @author tjq
 * @since 2020/4/17
 */
@Slf4j
public abstract class MapProcessor implements BasicProcessor {

    private static final int RECOMMEND_BATCH_SIZE = 200;
    private static final int REQUEST_TIMEOUT_MS = 5000;

    /**
     * 分发子任务
     * @param taskList 子任务，再次执行时可通过 TaskContext#getSubTask 获取
     * @param taskName 子任务名称，即子任务处理器中 TaskContext#getTaskName 获取到的值
     * @return map结果
     */
    public ProcessResult map(List<?> taskList, String taskName) {

        if (CollectionUtils.isEmpty(taskList)) {
            return new ProcessResult(false, "taskList can't be null");
        }

        if (taskList.size() > RECOMMEND_BATCH_SIZE) {
            log.warn("[MapProcessor] map task size is too large, network maybe overload... please try to split the tasks.");
        }

        TaskDO task = ThreadLocalStore.getTask();

        // 1. 构造请求
        ProcessorMapTaskRequest req = new ProcessorMapTaskRequest(task, taskList, taskName);

        // 2. 可靠发送请求（任务不允许丢失，需要使用 ask 方法，失败抛异常）
        boolean requestSucceed = false;
        try {
            String akkaRemotePath = AkkaUtils.getAkkaWorkerPath(task.getAddress(), RemoteConstant.Task_TRACKER_ACTOR_NAME);
            ActorSelection actorSelection = OhMyWorker.actorSystem.actorSelection(akkaRemotePath);
            CompletionStage<Object> requestCS = Patterns.ask(actorSelection, req, Duration.ofMillis(REQUEST_TIMEOUT_MS));
            AskResponse respObj = (AskResponse) requestCS.toCompletableFuture().get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            requestSucceed = respObj.isSuccess();
        }catch (Exception e) {
            log.warn("[MapProcessor] map failed.", e);
        }

        if (requestSucceed) {
            return new ProcessResult(true, "MAP_SUCCESS");
        }else {
            return new ProcessResult(false, "MAP_FAILED");
        }
    }

    /**
     * 是否为根任务
     * @return true -> 根任务 / false -> 非根任务
     */
    public boolean isRootTask() {
        TaskDO task = ThreadLocalStore.getTask();
        return TaskConstant.ROOT_TASK_NAME.equals(task.getTaskName());
    }
}
