package com.user.clionluogu.service

/**
 * 「先删子项、后删目录」的通用序。
 *
 * 抽出来只为一个原因：`VirtualFile` 要起 IDE 才能拿到，而这个顺序是删除会不会留下孤儿的
 * 关键判据，必须能在没平台的探针里跑。[AcCleanupService.deleteOrder] 就是把 `VirtualFile`
 * 的三个访问器传进来，走的是这一份代码。
 */
object DeleteOrder {

    /**
     * 深度优先、**后序**展开 [targets]：目录的所有子项排在它自己前面。
     *
     * 超过 [maxDepth] 的目录不再深入，只把目录本身排进去——删的时候它会因非空而失败，
     * 于是「N 项失败」如实报出来，而不是静默留一地孤儿文件。
     */
    @JvmStatic
    fun <T> orderOf(
        targets: List<T>,
        isDir: (T) -> Boolean,
        childrenOf: (T) -> List<T>,
        maxDepth: Int,
    ): List<T> {
        val out = ArrayList<T>()

        fun walk(item: T, depth: Int) {
            if (!isDir(item)) {
                out.add(item)
                return
            }
            if (depth < maxDepth) {
                childrenOf(item).forEach { walk(it, depth + 1) }
            }
            out.add(item)
        }

        targets.forEach { walk(it, 0) }
        return out
    }
}
