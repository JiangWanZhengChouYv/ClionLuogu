package com.user.clionluogu.storage

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * 自测输入（stdin）的项目级持久化：按题号记住那份手打的输入。
 *
 * 为什么不落盘成 `.in` 文件：项目里的目录名是有口径的（`Pxxx_samples/`、`Pxxx_cases/`），
 * [com.user.clionluogu.service.ProblemIndexService] 与
 * [com.user.clionluogu.service.AcCleanupService] 只认那几个精确名字去删。
 * 再多造一个目录就等于多一处「删不到 / 删错」的风险，而这份输入本来就只是临时试一下。
 * 所以放 XML（与提交记录同一套路），想留档他自己复制到 `Pxxx_cases/`。
 *
 * 也**不放** [com.user.clionluogu.settings.LuoguSettings] —— 那是 application 级的，
 * A 题的输入会串到 B 题的项目里。
 */
@Service(Service.Level.PROJECT)
@State(name = "ClionLuoguSelfTest", storages = [Storage("clionluoguSelfTest.xml")])
class SelfTestInputService : PersistentStateComponent<SelfTestInputService.State> {

    /** 一道题的那份输入。[pid] 恒为大写规范化形式。 */
    class Entry {
        var pid: String = ""
        var text: String = ""
    }

    /** 持久化根状态。 */
    class State {
        var entries: MutableList<Entry> = mutableListOf()
    }

    private var state = State()

    @Synchronized
    override fun getState(): State {
        val copy = State()
        // 每个字段都要镜像：漏一个就是「重启后那份输入没了」（1.7.2 踩过同款）
        copy.entries = state.entries.map { e ->
            Entry().apply {
                pid = e.pid
                text = e.text
            }
        }.toCollection(mutableListOf())
        return copy
    }

    @Synchronized
    override fun loadState(state: State) {
        this.state = State().apply {
            entries = state.entries.orEmpty().map { e ->
                Entry().apply {
                    pid = e.pid
                    text = e.text
                }
            }.toCollection(mutableListOf())
        }
    }

    /** 取某题的输入；没存过时为空串（面板直接显示空文本框，比 null 好办）。 */
    fun load(pid: String): String = textOf(state.entries, pid)

    /** 存某题的输入；[text] 空白时删掉条目，免得 XML 里堆一串空壳。 */
    fun save(pid: String, text: String) {
        state.entries = putText(state.entries, pid, text)
    }

    companion object {

        /** 存储键：与 [com.user.clionluogu.api.LuoguPidValidator] 的规范化口径一致（大写）。 */
        @JvmStatic
        fun normalizedKey(pid: String): String = pid.trim().uppercase()

        @JvmStatic
        fun textOf(entries: List<Entry>, pid: String): String {
            val key = normalizedKey(pid)
            return entries.firstOrNull { normalizedKey(it.pid) == key }?.text.orEmpty()
        }

        /** 返回**新列表**（不改入参）：有内容就覆盖/追加，全空白就移除该题。 */
        @JvmStatic
        fun putText(entries: List<Entry>, pid: String, text: String): MutableList<Entry> {
            val key = normalizedKey(pid)
            val kept = entries.filter { normalizedKey(it.pid) != key }.toMutableList()
            if (text.isNotBlank()) {
                kept.add(Entry().apply {
                    this.pid = key
                    this.text = text
                })
            }
            return kept
        }

        fun getInstance(project: Project): SelfTestInputService =
            project.getService(SelfTestInputService::class.java)
    }
}
