package com.remophoto.domain.model

/** 相册列表专用排序，和相册内图片排序相互独立。 */
enum class AlbumSortOrder(val displayName: String) {
    NAME_ASC("名称（A→Z）"),
    NAME_DESC("名称（Z→A）"),
    MODIFIED_ASC("修改时间（旧→新）"),
    MODIFIED_DESC("修改时间（新→旧）"),
    IMAGE_COUNT_ASC("图片数量（少→多）"),
    IMAGE_COUNT_DESC("图片数量（多→少）");

    companion object {
        val DEFAULT = NAME_ASC

        fun fromName(name: String?): AlbumSortOrder =
            name?.let { stored -> entries.find { it.name == stored } } ?: DEFAULT
    }
}
