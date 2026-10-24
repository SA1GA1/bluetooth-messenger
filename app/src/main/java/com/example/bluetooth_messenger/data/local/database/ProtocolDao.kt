package com.example.bluetooth_messenger.data.local.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProtocolDao {
    @Query("SELECT * FROM pairings WHERE expiresAt <= :now") suspend fun expiredPairings(now: Long): List<PairingEntity>
    @Query("SELECT COUNT(*) FROM pairings") suspend fun pairingCount(): Int
    @Query("SELECT * FROM pairings") fun pairings(): Flow<List<PairingEntity>>
    @Query("SELECT * FROM pairings WHERE peerId = :peer") suspend fun pairing(peer: String): PairingEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePairing(pairing: PairingEntity)
    @Query("DELETE FROM pairings WHERE peerId = :peer") suspend fun deletePairing(peer: String)
    @Query("DELETE FROM pairings WHERE expiresAt <= :now") suspend fun expirePairings(now: Long)
    @Query("SELECT * FROM group_members WHERE groupId = :group AND epoch = :epoch") suspend fun members(group: String, epoch: Int): List<GroupMemberEntity>
    @Query("SELECT * FROM group_members WHERE groupId = :group AND epoch = (SELECT epoch FROM conversations WHERE id = :group)") fun observeMembers(group: String): Flow<List<GroupMemberEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun saveMembers(members: List<GroupMemberEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun saveGroupKey(key: GroupKeyEntity)
    @Query("SELECT * FROM group_keys WHERE groupId = :group AND epoch = :epoch") suspend fun groupKey(group: String, epoch: Int): GroupKeyEntity?
    @Query("UPDATE conversations SET epoch = :epoch, active = :active WHERE id = :group AND epoch <= :epoch") suspend fun updateGroup(group: String, epoch: Int, active: Boolean)
    @Query("UPDATE conversations SET active = 0, leftAtEpoch = epoch WHERE id = :group") suspend fun leaveLocally(group: String)
    @Query("SELECT * FROM public_messages WHERE expiresAt > :now ORDER BY timestamp ASC") fun publicMessages(now: Long): Flow<List<PublicMessageEntity>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun savePublic(message: PublicMessageEntity)
    @Query("DELETE FROM public_messages WHERE expiresAt <= :now") suspend fun expirePublic(now: Long)
    @Query("DELETE FROM public_messages WHERE id NOT IN (SELECT id FROM public_messages ORDER BY timestamp DESC LIMIT 2000)") suspend fun trimPublic()
}
