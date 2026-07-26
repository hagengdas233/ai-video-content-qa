package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import com.example.server.utils.DeepSeekUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AgentLoopService {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopService.class);
    private static final int MAX_ROUNDS = 2;

    @Autowired
    private DeepSeekUtils deepSeekUtils;

    @Autowired
    private LongVideoContextService longVideoContextService;

    public AgentState run(VideoContext context) {
        VideoContext relevantContext = longVideoContextService.selectRelevant(context);
        AgentState.AgentPlan plan = deepSeekUtils.plan(relevantContext);
        AgentState state = new AgentState(relevantContext.userGoal(), plan, null, null, 0);

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            AnalysisResult result;
            try {
                result = deepSeekUtils.execute(relevantContext, plan, state.critique());
            } catch (RuntimeException e) {
                if (state.result() == null) {
                    throw e;
                }
                log.warn("Executor correction failed in round {}; falling back to the previous round result, "
                        + "failureType={}", round, e.getClass().getSimpleName());
                return state;
            }
            AgentState.CriticResult critique;
            try {
                critique = deepSeekUtils.critique(relevantContext, plan, result);
            } catch (Exception e) {
                log.warn("Critic failed in round {}; falling back to the current Executor result, failureType={}",
                        round, e.getClass().getSimpleName());
                return new AgentState(relevantContext.userGoal(), plan, result, null, round);
            }
            state = new AgentState(relevantContext.userGoal(), plan, result, critique, round);

            if (critique.passed()) {
                break;
            }
        }
        return state;
    }
}
