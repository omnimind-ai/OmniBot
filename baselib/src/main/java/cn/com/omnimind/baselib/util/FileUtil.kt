package cn.com.omnimind.baselib.util

import java.io.File

object FileUtil {
    const val TAG = "FileUtil"

    /**
     * 确保目录存在
     * @param dirFile 目标目录
     */
    fun ensureDir(dirFile: File?) {
        dirFile ?: return
        if (dirFile.isFile) return
        ensureDirAndClear(dirFile.absolutePath, false)
    }

    /**
     * 确保目录存在，若存在则清空其所有内容
     * @param dirFile 目标目录
     */
    fun ensureDirAndClear(dirFile: File?) {
        dirFile ?: return
        if (dirFile.isFile) return
        ensureDirAndClear(dirFile.absolutePath, true)
    }

    /**
     * 确保目录存在，若存在则清空其所有内容
     * @param dirPath 目标目录的路径字符串
     * @param isClear 是否清空目录中的内容
     */
    private fun ensureDirAndClear(dirPath: String, isClear: Boolean) {
        val dir = File(dirPath)
        try {
            if (dir.exists()) {
                if (isClear) {
                    clearDirectory(dir)
                }
            } else {
                val isCreated = dir.mkdirs()
                if (isCreated) {
                    OmniLog.i(TAG, "Directory created")
                } else {
                    throw RuntimeException("创建目录失败：$dirPath")
                }
            }
        } catch (e: Exception) {
            OmniLog.e(TAG, "Directory operation failed type=${e.javaClass.simpleName}")
            throw RuntimeException("处理目录失败：${e.message}", e)
        }
    }

    /**
     * 递归清空目录
     * @param dir 目标目录的File对象
     */
    fun clearDirectory(dir: File) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isFile) {
                val isDeleted = file.delete()
                if (!isDeleted) {
                    throw RuntimeException("删除文件失败：${file.absolutePath}")
                }
            } else if (file.isDirectory) {
                clearDirectory(file)
                val isDeleted = file.delete()
                if (!isDeleted) {
                    throw RuntimeException("删除子目录失败：${file.absolutePath}")
                }
            }
        }
        OmniLog.i(TAG, "Directory cleared")
    }

    fun deleteFile(file: File) {
        if (file.isFile) {
            val isDeleted = file.delete()
        }
    }
}
