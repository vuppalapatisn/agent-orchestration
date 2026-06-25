package com.example.agentorchestration.orchestration;

import com.example.agentorchestration.dto.WorkerResult;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class ParallelExecutionService {

    @SafeVarargs
    public final List<WorkerResult> joinAll(CompletableFuture<WorkerResult>... futures) {
        CompletableFuture.allOf(futures).join();
        return Arrays.stream(futures).map(CompletableFuture::join).toList();
    }
}
