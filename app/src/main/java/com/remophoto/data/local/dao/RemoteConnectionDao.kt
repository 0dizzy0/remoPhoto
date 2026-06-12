package com.remophoto.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.remophoto.data.local.entity.ConnectionStatus
import com.remophoto.data.local.entity.RemoteConnectionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 远程连接配置 DAO
 */
@Dao
interface RemoteConnectionDao {

    /** 查询所有远程连接（Flow 响应式） */
    @Query("SELECT * FROM remote_connections ORDER BY added_time DESC")
    fun getAllConnections(): Flow<List<RemoteConnectionEntity>>

    /** 查询所有远程连接（挂起函数） */
    @Query("SELECT * FROM remote_connections ORDER BY added_time DESC")
    suspend fun getAllConnectionsList(): List<RemoteConnectionEntity>

    /** 根据 ID 查询连接 */
    @Query("SELECT * FROM remote_connections WHERE id = :connectionId LIMIT 1")
    suspend fun getConnectionById(connectionId: Long): RemoteConnectionEntity?

    /** 根据类型查询连接 */
    @Query("SELECT * FROM remote_connections WHERE type = :type ORDER BY added_time DESC")
    fun getConnectionsByType(type: String): Flow<List<RemoteConnectionEntity>>

    /** 根据主机和端口查找连接（用于去重） */
    @Query("SELECT * FROM remote_connections WHERE host = :host AND port = :port LIMIT 1")
    suspend fun getConnectionByHostAndPort(host: String, port: Int): RemoteConnectionEntity?

    /** 插入连接 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connection: RemoteConnectionEntity): Long

    /** 更新连接 */
    @Update
    suspend fun update(connection: RemoteConnectionEntity)

    /** 删除连接 */
    @Delete
    suspend fun delete(connection: RemoteConnectionEntity)

    /** 根据 ID 删除连接 */
    @Query("DELETE FROM remote_connections WHERE id = :connectionId")
    suspend fun deleteById(connectionId: Long)

    /** 更新连接状态 */
    @Query("UPDATE remote_connections SET status = :status WHERE id = :connectionId")
    suspend fun updateStatus(connectionId: Long, status: ConnectionStatus)

    /** 更新最后连接时间 */
    @Query("UPDATE remote_connections SET last_connected_time = :timestamp WHERE id = :connectionId")
    suspend fun updateLastConnectedTime(connectionId: Long, timestamp: Long)

    /** 获取连接总数 */
    @Query("SELECT COUNT(*) FROM remote_connections")
    suspend fun getConnectionCount(): Int
}
