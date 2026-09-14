package com.user.clionluogu.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.application.ApplicationManager

/**
 * 应用级持久化设置：保存生成 .cpp 用的 C++ 代码模板。
 */
@State(name = "ClionLuoguSettings", storages = [Storage("clionluogu.xml")])
@Service
class LuoguSettings : PersistentStateComponent<LuoguSettings> {

    companion object {
        @Volatile
        private var instance: LuoguSettings? = null

        fun getInstance(): LuoguSettings =
            instance ?: synchronized(this) {
                instance ?: ApplicationManager.getApplication()
                    .getService(LuoguSettings::class.java).also { instance = it }
            }

        /** 内置默认 C++ 竞赛模板。 */
        const val DEFAULT_CODE_TEMPLATE: String = (
            "#include <bits/stdc++.h>\n" +
                "using namespace std;\n" +
                "\n" +
                "int main() {\n" +
                "    ios::sync_with_stdio(false);\n" +
                "    cin.tie(nullptr);\n" +
                "\n" +
                "    return 0;\n" +
                "}"
            )
    }

    /** 用户自定义模板；若为 null/空则返回内置默认。 */
    private var template: String? = null

    var codeTemplate: String
        get() = if (template.isNullOrBlank()) DEFAULT_CODE_TEMPLATE else template!!
        set(value) {
            template = value
        }

    override fun getState(): LuoguSettings = this

    override fun loadState(state: LuoguSettings) {
        this.template = state.template
    }
}