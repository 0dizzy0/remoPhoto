package com.remophoto.domain.model

/**
 * 图片排序策略枚举
 *
 * 6 种排序选项，默认 DATE_MODIFIED_ASC（修改时间升序，旧→新）
 */
enum class SortOrder(val displayName: String) {
    /** 修改时间 旧→新（⭐默认） */
    DATE_MODIFIED_ASC("修改时间（旧→新）"),

    /** 修改时间 新→旧 */
    DATE_MODIFIED_DESC("修改时间（新→旧）"),

    /** 文件名 A→Z */
    NAME_ASC("文件名（A→Z）"),

    /** 文件名 Z→A */
    NAME_DESC("文件名（Z→A）"),

    /** 文件大小 小→大 */
    SIZE_ASC("文件大小（小→大）"),

    /** 文件大小 大→小 */
    SIZE_DESC("文件大小（大→小）");

    companion object {
        /** 默认排序方式 */
        val DEFAULT: SortOrder = DATE_MODIFIED_ASC

        /** 从名称字符串解析 */
        fun fromName(name: String?): SortOrder =
            name?.let { n -> entries.find { it.name == n } } ?: DEFAULT
    }
}
