package com.rampey.materialtv;

import com.rampey.voice.Act;
import com.rampey.voice.Understand;
import com.rampey.voice.Utterance;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.Function;

/** The launcher's stages for the voice loop (Hear -> Understand -> Act -> Reply). Hearing stays in the internal voice package;
 *  Reply is the launcher's own pill, so it has no stage here. */
final class VoiceStages {
    private VoiceStages() {
    }

    /** Understand: send the utterance to Gemini through OpenRouter. */
    static final class Brain implements Understand<JSONObject, VoiceAgent.Result> {
        private final VoiceAgent agent;
        Brain(VoiceAgent agent) { this.agent = agent; }
        @Override public VoiceAgent.Result understand(Utterance u, JSONObject context) {
            return u.text != null ? agent.ask(u.text, context) : agent.askAudio(u.audio, context);
        }
    }

    /** Act: the plan's actions, run by the launcher (open apps, switch inputs, ...). */
    static final class Actions implements Act<VoiceAgent.Result> {
        private final Function<JSONArray, String> run;

        Actions(Function<JSONArray, String> run) {
            this.run = run;
        }

        @Override public String act(VoiceAgent.Result plan) {
            return run.apply(plan.actions);
        }
    }
}
