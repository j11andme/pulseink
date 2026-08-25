<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  getEvaluationCases,
  getLatestEvaluationReport,
  runCustomEvaluation,
  runEvaluation,
  type EvaluationChannel,
  type EvaluationCaseSummary,
  type EvaluationPolicy,
  type EvaluationReport,
  type EvaluationSuite
} from "../api/evaluation";
import { ApiError } from "../api/http";
import AppShell from "../components/layout/AppShell.vue";
import { useAuthStore } from "../stores/auth";
import { formatDateTime } from "../utils/date";

const policies: EvaluationPolicy[] = ["DIRECT", "REACT", "ORCHESTRATED", "ADAPTIVE"];
const executionModes = ["DIRECT", "REACT", "ORCHESTRATED"] as const;
const auth = useAuthStore();
const cases = ref<EvaluationCaseSummary[]>([]);
const smokeCount = ref(0);
const report = ref<EvaluationReport>();
const suite = ref<EvaluationSuite>("SMOKE");
const judgeEnabled = ref(false);
const selectedPolicies = ref<EvaluationPolicy[]>(["DIRECT"]);
const customTask = ref("");
const customExpectedResult = ref("");
const customAudience = ref("通用内容受众");
const customChannel = ref<EvaluationChannel>("SOCIAL");
const customConstraints = ref("");
const loading = ref(true);
const running = ref(false);
const errorMessage = ref("");

const canRun = computed(() => auth.user?.role === "EDITOR" || auth.user?.role === "ADMIN");
const isCustom = computed(() => suite.value === "CUSTOM");
const customReady = computed(() =>
  customTask.value.trim().length > 0 &&
  customExpectedResult.value.trim().length > 0 &&
  customAudience.value.trim().length > 0 &&
  selectedPolicies.value.length > 0
);
const judgeFailures = computed(() =>
  report.value?.executions.filter((item) => item.judge.parseFailure).length ?? 0
);

async function load() {
  loading.value = true;
  errorMessage.value = "";
  try {
    const [catalog, latest] = await Promise.all([
      getEvaluationCases(auth.accessToken!),
      getLatestEvaluationReport(auth.accessToken!)
    ]);
    cases.value = catalog.cases;
    smokeCount.value = catalog.smokeCount;
    report.value = latest;
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : "评测数据加载失败";
  } finally {
    loading.value = false;
  }
}

async function execute() {
  if (!canRun.value || running.value) return;
  running.value = true;
  errorMessage.value = "";
  try {
    if (isCustom.value) {
      if (!customReady.value) {
        errorMessage.value = "请填写任务、参考结果和受众，并至少选择一种执行策略";
        return;
      }
      report.value = await runCustomEvaluation(auth.accessToken!, {
        task: customTask.value.trim(),
        expectedResult: customExpectedResult.value.trim(),
        audience: customAudience.value.trim(),
        channel: customChannel.value,
        constraints: customConstraints.value.split(/\r?\n/)
          .map((value) => value.trim()).filter(Boolean),
        policies: selectedPolicies.value
      });
    } else {
      report.value = await runEvaluation(auth.accessToken!, {
        suite: suite.value,
        policies,
        judgeEnabled: judgeEnabled.value
      });
    }
  } catch (error) {
    errorMessage.value = error instanceof ApiError ? error.message : "评测执行失败";
  } finally {
    running.value = false;
  }
}

function percent(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}

function number(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

function modeCount(mode: (typeof executionModes)[number]): number {
  return report.value?.selectedModeDistribution[mode] ?? 0;
}

onMounted(load);
</script>

<template>
  <AppShell active-route="evaluation">
    <div class="evaluation-view">
      <header class="evaluation-header">
        <div>
          <p class="eyebrow">PULSEINK-EVAL</p>
          <h1>Evaluation Lab</h1>
          <p>
            在固定 Case、知识与搜索结果下比较 DIRECT、REACT、ORCHESTRATED 和 AUTO。
            ADAPTIVE 是选择策略，最终仍落到前三种实际执行引擎之一。
          </p>
        </div>
        <div class="dataset-meta">
          <span data-testid="evaluation-case-count"><strong>{{ cases.length }}</strong> Cases</span>
          <span data-testid="evaluation-smoke-count"><strong>{{ smokeCount }}</strong> Smoke</span>
        </div>
      </header>

      <div v-if="loading" class="evaluation-state">正在加载评测目录…</div>
      <template v-else>
        <section class="evaluation-controls">
          <div>
            <label for="evaluation-suite">评测套件</label>
            <select id="evaluation-suite" v-model="suite" :disabled="running">
              <option value="SMOKE">Smoke · 6 Cases × applicable policies</option>
              <option value="FULL">Full · 18 Cases × applicable policies</option>
              <option value="STABILITY">Stability · Smoke × applicable policies × 3</option>
              <option value="CUSTOM">Custom · 自定义单个 Case</option>
            </select>
          </div>
          <label v-if="!isCustom" class="judge-option">
            <input v-model="judgeEnabled" type="checkbox" :disabled="running" />
            启用匿名 AB/BA LLM Judge
          </label>
          <p v-else class="custom-judge-note">Custom 固定启用参考结果语义 Judge</p>
          <button
            v-if="canRun"
            class="primary-button"
            type="button"
            data-testid="run-evaluation"
            :disabled="running || (isCustom && !customReady)"
            @click="execute"
          >
            {{ running ? "评测运行中…" : "运行评测" }}
          </button>
          <p v-else class="read-only-note">当前角色为只读 Viewer，不能启动模型评测。</p>
        </section>

        <section v-if="isCustom" class="custom-case-form" data-testid="custom-case-form">
          <header>
            <div>
              <h2>自定义 Case</h2>
              <p>参考结果只交给 Judge，不会发送给被测 Agent；报告独立标记为 CUSTOM。</p>
            </div>
            <span>不会写入固定 18 Case Catalog</span>
          </header>
          <div class="custom-form-grid">
            <label class="wide-field">
              测评任务
              <textarea
                v-model="customTask"
                data-testid="custom-task"
                maxlength="2000"
                placeholder="例如：为 Java 秋招活动撰写一段社媒招聘内容"
                :disabled="running"
              />
            </label>
            <label class="wide-field">
              参考结果
              <textarea
                v-model="customExpectedResult"
                data-testid="custom-expected-result"
                maxlength="4000"
                placeholder="描述理想答案，Judge 将按语义而不是逐字匹配评分"
                :disabled="running"
              />
            </label>
            <label>
              目标受众
              <input
                v-model="customAudience"
                data-testid="custom-audience"
                maxlength="200"
                :disabled="running"
              />
            </label>
            <label>
              发布渠道
              <select v-model="customChannel" data-testid="custom-channel" :disabled="running">
                <option value="SOCIAL">SOCIAL</option>
                <option value="BLOG">BLOG</option>
                <option value="SHORT_VIDEO">SHORT_VIDEO</option>
              </select>
            </label>
            <label class="wide-field">
              附加要求（可选，每行一项）
              <textarea
                v-model="customConstraints"
                data-testid="custom-constraints"
                placeholder="例如：语气专业&#10;不编造薪资数据"
                :disabled="running"
              />
            </label>
          </div>
          <fieldset class="custom-policy-options">
            <legend>执行策略（至少选择一种）</legend>
            <label v-for="policy in policies" :key="policy">
              <input
                v-model="selectedPolicies"
                type="checkbox"
                :value="policy"
                :data-testid="`custom-policy-${policy}`"
                :disabled="running"
              />
              {{ policy }}
            </label>
          </fieldset>
          <p class="custom-safety-note">
            自定义 Case 使用真实模型并会写入本地评测报告，请勿输入密钥或个人敏感信息。
          </p>
        </section>

        <p v-if="errorMessage" class="evaluation-error">{{ errorMessage }}</p>

        <section v-if="!report" class="evaluation-state">
          尚无评测报告。可先运行无需外部 API Key 的 Smoke 套件。
        </section>

        <template v-else>
          <section class="report-heading">
            <div>
              <span>{{ report.reportId }}</span>
              <strong>{{ report.suite }}</strong>
              <strong>{{ report.runtime.provider }} / {{ report.runtime.model }}</strong>
              <span>{{ report.runtime.simulated ? "SIMULATED" : "REAL MODEL" }}</span>
              <small>{{ formatDateTime(report.generatedAt) }}</small>
              <small v-if="report.replayedFromReportId">replay: {{ report.replayedFromReportId }}</small>
            </div>
            <p>
              {{ report.scoredSamples }} scored · {{ report.errorSamples }} error ·
              {{ report.judgeUnscoredSamples }} judge unscored ·
              invalid {{ percent(report.invalidSampleRate) }}；<strong>不声明统计显著性</strong>。
            </p>
          </section>

          <section v-if="report.customCase" class="custom-case-summary">
            <div><strong>任务</strong><p>{{ report.customCase.task }}</p></div>
            <div><strong>参考结果</strong><p>{{ report.customCase.expectedResult }}</p></div>
            <div><strong>受众 / 渠道</strong><p>{{ report.customCase.audience }} / {{ report.customCase.channel }}</p></div>
            <div v-if="report.customCase.constraints.length">
              <strong>附加要求</strong><p>{{ report.customCase.constraints.join("；") }}</p>
            </div>
          </section>

          <section class="policy-grid" aria-label="策略汇总">
            <article v-for="summary in report.summaries" :key="summary.policy">
              <header><strong>{{ summary.policy }}</strong><span>{{ summary.executions }} runs</span></header>
              <dl>
                <div><dt>硬规则通过率</dt><dd>{{ percent(summary.passRate) }}</dd></div>
                <div><dt>可评分 / Error</dt><dd>{{ summary.scoredExecutions }} / {{ summary.errors }}</dd></div>
                <div><dt>Quality n / 未评分</dt><dd>{{ summary.qualitySamples }} / {{ summary.judgeUnscored }}</dd></div>
                <div><dt>Quality</dt><dd>{{ summary.qualitySamples ? summary.averageQuality.toFixed(3) : "—" }}</dd></div>
                <div><dt>Quality σ</dt><dd>{{ summary.qualityStdDev.toFixed(3) }}</dd></div>
                <div><dt>Groundedness</dt><dd>{{ summary.averageGroundedness.toFixed(3) }}</dd></div>
                <div><dt>平均 Tokens</dt><dd>{{ number(summary.averageTokens) }}</dd></div>
                <div><dt>平均耗时</dt><dd>{{ number(summary.averageLatencyMs) }} ms</dd></div>
                <div><dt>耗时 σ</dt><dd>{{ number(summary.latencyStdDev) }} ms</dd></div>
                <div><dt>协调 Artifact</dt><dd>{{ number(summary.averageCoordinationArtifacts) }}</dd></div>
              </dl>
            </article>
          </section>

          <section class="mode-section">
            <h2>实际 Selected Mode 分布</h2>
            <div class="mode-grid">
              <div v-for="mode in executionModes" :key="mode">
                <span>{{ mode }}</span>
                <strong>{{ modeCount(mode) }}</strong>
              </div>
            </div>
            <p v-if="judgeFailures > 0" class="judge-warning">
              {{ judgeFailures }} 条结果记录了 JUDGE_PARSE_FAILURE；硬规则结果未被覆盖。
            </p>
          </section>

          <section class="ablation-section">
            <h2>REACT vs ORCHESTRATED 消融</h2>
            <p>协调开销同时考虑 Token 与耗时；倍数越高，表示多 Agent 协同成本越大。</p>
            <div v-if="report.comparisons.length" class="ablation-grid">
              <article
                v-for="comparison in report.comparisons"
                :key="`${comparison.caseId}-${comparison.repetition}`"
              >
                <div>
                  <strong>{{ comparison.caseId }}</strong>
                  <span>#{{ comparison.repetition }}</span>
                </div>
                <dl>
                  <template v-if="comparison.comparable">
                    <div><dt>Quality Δ</dt><dd>{{ comparison.qualityDelta.toFixed(3) }}</dd></div>
                    <div><dt>协调开销</dt><dd>{{ comparison.coordinationOverhead.toFixed(2) }}×</dd></div>
                    <div><dt>推荐策略</dt><dd>{{ comparison.preferredPolicy }}</dd></div>
                  </template>
                  <div v-else><dt>状态</dt><dd>UNSCORED · {{ comparison.reason }}</dd></div>
                </dl>
              </article>
            </div>
            <p v-else class="empty-ablation">当前策略组合不包含 REACT 与 ORCHESTRATED，无法生成成对消融。</p>
          </section>

          <section class="result-section">
            <h2>逐 Case 结果</h2>
            <div class="result-table-wrap">
              <table>
                <thead>
                  <tr><th>Case</th><th>Policy → Mode</th><th>状态</th><th>RAG</th><th>调用/Token</th><th>Repair/协调</th><th>失败证据</th></tr>
                </thead>
                <tbody>
                  <tr v-for="item in report.executions" :key="`${item.execution.caseId}-${item.repetition}-${item.execution.policy}`">
                    <td><strong>{{ item.execution.caseId }}</strong><small>#{{ item.repetition }}</small></td>
                    <td>{{ item.execution.policy }} → {{ item.execution.selectedMode }}</td>
                    <td>
                      <span :class="item.score.status === 'ERROR' ? 'result-error' : item.score.passed ? 'result-pass' : 'result-fail'">
                        {{ item.score.status === "ERROR" ? "ERROR" : item.score.passed ? "PASS" : "FAIL" }}
                      </span>
                      <small>{{ item.execution.finalState }} / {{ item.execution.terminalReason }}</small>
                    </td>
                    <td>R@K {{ item.score.recallAtK.toFixed(2) }} · nDCG {{ item.score.ndcg.toFixed(2) }}</td>
                    <td>{{ item.execution.modelCalls }}M / {{ item.execution.toolCalls }}T · {{ item.execution.totalTokens }}</td>
                    <td>{{ item.execution.repairCount }} / {{ item.execution.coordinationArtifacts }}</td>
                    <td>
                      <strong v-if="item.score.failure.stage !== 'NONE'">
                        {{ item.score.failure.stage }} / {{ item.score.failure.code }}
                      </strong>
                      <span v-if="item.score.failure.summary">{{ item.score.failure.summary }}</span>
                      <span v-else-if="item.judge.status === 'UNSCORED'">{{ item.judge.failureCode }} · {{ item.judge.explanation }}</span>
                      <span v-else-if="item.judge.status === 'SCORED'">Judge · {{ item.judge.explanation }}</span>
                      <span v-else>—</span>
                      <details v-if="item.execution.trace.length">
                        <summary>轨迹 {{ item.execution.trace.length }} steps</summary>
                        <ol>
                          <li v-for="step in item.execution.trace" :key="step.sequence">
                            #{{ step.sequence }} {{ step.actor }} · {{ step.eventType }} ·
                            {{ step.subject }} · {{ step.outcome }}
                            <small v-if="step.summary">{{ step.summary }}</small>
                          </li>
                        </ol>
                      </details>
                      <details v-if="item.execution.candidateText" class="candidate-output">
                        <summary>查看候选输出</summary>
                        <pre>{{ item.execution.candidateText }}</pre>
                      </details>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </template>
      </template>
    </div>
  </AppShell>
</template>

<style scoped>
.evaluation-view { display: grid; gap: 1.4rem; }
.evaluation-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 1.5rem; }
.evaluation-header h1 { margin: 0; color: #172033; font-size: 2rem; letter-spacing: -0.035em; }
.evaluation-header p:not(.eyebrow) { max-width: 56rem; margin: .75rem 0 0; color: #596277; line-height: 1.75; }
.dataset-meta { display: flex; gap: .6rem; white-space: nowrap; }
.dataset-meta span { padding: .65rem .8rem; border: 1px solid #e4e8f0; border-radius: .7rem; background: #fff; color: #667085; }
.dataset-meta strong { color: #5c5ce6; }
.evaluation-controls, .custom-case-form, .custom-case-summary, .report-heading, .mode-section, .ablation-section, .result-section { padding: 1.2rem; border: 1px solid #e4e8f0; border-radius: 1rem; background: #fff; }
.evaluation-controls { display: flex; align-items: end; gap: 1rem; }
.evaluation-controls > div { display: grid; gap: .4rem; }
.evaluation-controls label { color: #344054; font-size: .82rem; font-weight: 700; }
.evaluation-controls select { min-width: 14rem; padding: .65rem .75rem; border: 1px solid #d4d8e5; border-radius: .65rem; background: #fff; }
.judge-option { display: flex; align-items: center; gap: .45rem; padding-bottom: .65rem; }
.custom-judge-note { margin: 0; padding-bottom: .65rem; color: #5c5ce6; font-size: .82rem; font-weight: 700; }
.evaluation-controls .primary-button { margin-left: auto; }
.custom-case-form { display: grid; gap: 1rem; }
.custom-case-form header { display: flex; justify-content: space-between; gap: 1rem; }
.custom-case-form h2 { margin: 0; font-size: 1.05rem; }
.custom-case-form header p, .custom-case-form header span, .custom-safety-note { margin: .35rem 0 0; color: #667085; font-size: .8rem; }
.custom-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .9rem; }
.custom-form-grid label { display: grid; gap: .4rem; color: #344054; font-size: .82rem; font-weight: 700; }
.custom-form-grid .wide-field { grid-column: 1 / -1; }
.custom-form-grid input, .custom-form-grid select, .custom-form-grid textarea { width: 100%; box-sizing: border-box; padding: .7rem .75rem; border: 1px solid #d4d8e5; border-radius: .65rem; background: #fff; color: #172033; font: inherit; }
.custom-form-grid textarea { min-height: 5.5rem; resize: vertical; }
.custom-policy-options { display: flex; flex-wrap: wrap; gap: .8rem 1.2rem; margin: 0; padding: .9rem; border: 1px solid #e4e8f0; border-radius: .7rem; }
.custom-policy-options legend { padding: 0 .35rem; color: #344054; font-size: .8rem; font-weight: 700; }
.custom-policy-options label { display: flex; align-items: center; gap: .4rem; color: #596277; font-size: .82rem; }
.custom-case-summary { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: .8rem; }
.custom-case-summary div { padding: .75rem; border-radius: .7rem; background: #f7f8fb; }
.custom-case-summary strong { color: #5c5ce6; font-size: .78rem; }
.custom-case-summary p { margin: .35rem 0 0; color: #344054; white-space: pre-wrap; }
.read-only-note { margin: 0 0 .6rem auto; color: #667085; }
.evaluation-state, .evaluation-error { padding: 1.5rem; border: 1px solid #e4e8f0; border-radius: 1rem; background: #fff; color: #667085; text-align: center; }
.evaluation-error { border-color: #f0caca; color: #a52e2e; background: #fff5f5; }
.report-heading { display: flex; justify-content: space-between; gap: 1rem; }
.report-heading div { display: flex; align-items: baseline; gap: .7rem; }
.report-heading span, .report-heading small { color: #667085; }
.report-heading strong { color: #5c5ce6; }
.report-heading p { margin: 0; color: #667085; }
.policy-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: .8rem; }
.policy-grid article { padding: 1rem; border: 1px solid #e4e8f0; border-radius: .9rem; background: #fff; }
.policy-grid header { display: flex; justify-content: space-between; gap: .5rem; }
.policy-grid header strong { color: #5c5ce6; }
.policy-grid header span { color: #667085; font-size: .75rem; }
.policy-grid dl { display: grid; gap: .45rem; margin: .9rem 0 0; }
.policy-grid dl div { display: flex; justify-content: space-between; gap: .5rem; }
.policy-grid dt { color: #667085; font-size: .75rem; }
.policy-grid dd { margin: 0; color: #172033; font-size: .8rem; font-weight: 700; }
.mode-section h2, .ablation-section h2, .result-section h2 { margin: 0 0 .9rem; font-size: 1.05rem; }
.mode-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: .7rem; }
.mode-grid div { display: flex; justify-content: space-between; padding: .75rem; border-radius: .65rem; background: #f7f8fb; color: #596277; }
.mode-grid strong { color: #172033; }
.judge-warning { margin: .8rem 0 0; color: #a15c00; }
.ablation-section > p { margin: -.35rem 0 .9rem; color: #667085; font-size: .82rem; }
.ablation-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .7rem; }
.ablation-grid article { padding: .8rem; border: 1px solid #e8eaf1; border-radius: .7rem; background: #fafbfc; }
.ablation-grid article > div, .ablation-grid dl div { display: flex; justify-content: space-between; gap: .5rem; }
.ablation-grid article > div span, .ablation-grid dt { color: #667085; font-size: .75rem; }
.ablation-grid dl { display: grid; gap: .35rem; margin: .7rem 0 0; }
.ablation-grid dd { margin: 0; color: #172033; font-size: .78rem; font-weight: 700; }
.empty-ablation { margin: 0 !important; }
.result-table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; font-size: .78rem; }
th, td { padding: .7rem; border-bottom: 1px solid #eceef3; text-align: left; vertical-align: top; }
th { color: #667085; background: #f7f8fb; }
td small { display: block; margin-top: .25rem; color: #7a8294; }
.result-pass { color: #167454; font-weight: 800; }
.result-fail { color: #b33a3a; font-weight: 800; }
.result-error { color: #a15c00; font-weight: 800; }
td details { margin-top: .45rem; max-width: 28rem; }
td details summary { cursor: pointer; color: #5c5ce6; }
td details ol { display: grid; gap: .3rem; margin: .45rem 0 0; padding-left: 1.2rem; color: #596277; }
.candidate-output pre { max-height: 18rem; overflow: auto; white-space: pre-wrap; color: #344054; font: inherit; }
@media (max-width: 1100px) {
  .policy-grid { grid-template-columns: repeat(2, 1fr); }
  .ablation-grid { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 760px) {
  .evaluation-header, .evaluation-controls, .report-heading, .custom-case-form header { align-items: stretch; flex-direction: column; }
  .evaluation-controls .primary-button, .read-only-note { margin-left: 0; }
  .policy-grid, .mode-grid, .ablation-grid, .custom-form-grid, .custom-case-summary { grid-template-columns: 1fr; }
  .custom-form-grid .wide-field { grid-column: auto; }
}
</style>
