package com.user.clionluogu.storage

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.user.clionluogu.api.SubmissionStatus

/**
 * 项目级提交记录持久化服务。
 *
 * 保存每次提交的题号、rid、提交时间、语言与当次源码，以及最近一次评测状态快照
 * （状态码 / 总耗时 / 总内存 / 编译错误详情 / 逐子任务与逐测试点）。仅保存业务内容，
 * **不写入任何 cookie / 凭证**（凭证仍仅由 [SecureCookieStore] 经 PasswordSafe 保存）。
 */
@Service(Service.Level.PROJECT)
@State(name = "ClionLuoguHistory", storages = [Storage("clionluoguHistory.xml")])
class SubmissionHistoryService : PersistentStateComponent<SubmissionHistoryService.State> {

    /** 一条提交记录。 */
    class Record {
        var pid: String = ""
        var rid: String = ""
        var submitTime: Long = 0
        var lang: String = ""
        var code: String = ""
        var statusCode: Int? = null
        var timeMs: Long? = null
        var memoryKb: Long? = null
        var compileError: String? = null
        var subtasks: MutableList<SubtaskRecord> = mutableListOf()
    }

    /** 一个子任务的状态快照。 */
    class SubtaskRecord {
        var id: Int = 0
        var score: Int? = null
        var status: Int? = null
        var testCases: MutableList<TestCaseRecord> = mutableListOf()
    }

    /** 单个测试点的状态快照。 */
    class TestCaseRecord {
        var id: Int = 0
        var status: Int? = null
        var timeMs: Long? = null
        var memoryKb: Long? = null
        var score: Int? = null
    }

    /** 持久化根状态。 */
    class State {
        var records: MutableList<Record> = mutableListOf()
    }

    private var state = State()

    @Synchronized
    override fun getState(): State {
        val copy = State()
        copy.records = state.records.map { r ->
            Record().apply {
                pid = r.pid
                rid = r.rid
                submitTime = r.submitTime
                lang = r.lang
                code = r.code
                statusCode = r.statusCode
                timeMs = r.timeMs
                memoryKb = r.memoryKb
                compileError = r.compileError
                subtasks = r.subtasks.map { sub ->
                    SubtaskRecord().apply {
                        id = sub.id
                        score = sub.score
                        status = sub.status
                        testCases = sub.testCases.map { tc ->
                            TestCaseRecord().apply {
                                id = tc.id
                                status = tc.status
                                timeMs = tc.timeMs
                                memoryKb = tc.memoryKb
                                score = tc.score
                            }
                        }.toMutableList()
                    }
                }.toMutableList()
            }
        }.toMutableList()
        return copy
    }

    @Synchronized
    override fun loadState(state: State) {
        this.state = state
    }

    /** 返回全部记录（顺序即展示顺序）。 */
    @Synchronized
    fun list(): List<Record> = state.records.toList()

    /** 追加一条记录。 */
    @Synchronized
    fun add(record: Record) {
        state.records.add(record)
    }

    /**
     * 某题最近一次提交。记录按追加顺序存，所以取**最后**一条才是当前状态；
     * 题号大小写忽略（`p1001` 与 `P1001` 是同一题）。
     */
    @Synchronized
    fun latestFor(pid: String): Record? =
        state.records.lastOrNull { it.pid.equals(pid, ignoreCase = true) }

    /** 提交过的题号集合（统一大写），题库索引页的「只看未提交」用。 */
    @Synchronized
    fun submittedPids(): Set<String> = state.records.map { it.pid.uppercase() }.toSet()

    /** 用最新状态快照覆盖对应记录（含逐子任务与逐测试点）。 */
    @Synchronized
    fun update(rid: String, status: SubmissionStatus) {
        val record = state.records.firstOrNull { it.rid == rid } ?: return
        record.statusCode = status.statusCode
        record.timeMs = status.timeMs
        record.memoryKb = status.memoryKb
        record.compileError = status.compileError
        record.subtasks = status.subtaskResults.map { sub ->
            SubtaskRecord().apply {
                id = sub.id
                score = sub.score
                this.status = sub.status
                testCases = sub.testCases.map { tc ->
                    TestCaseRecord().apply {
                        id = tc.id
                        this.status = tc.status
                        timeMs = tc.timeMs
                        memoryKb = tc.memoryKb
                        score = tc.score
                    }
                }.toMutableList()
            }
        }.toMutableList()
    }

    /** 清空全部记录。 */
    @Synchronized
    fun clear() {
        state.records.clear()
    }

    companion object {
        fun getInstance(project: Project): SubmissionHistoryService =
            project.getService(SubmissionHistoryService::class.java)
    }
}
