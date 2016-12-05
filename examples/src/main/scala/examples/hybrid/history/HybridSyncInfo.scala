package examples.hybrid.history

import examples.hybrid.blocks.{PosBlock, PowBlock}
import scorex.core.NodeViewModifier
import scorex.core.NodeViewModifier.{ModifierId, ModifierTypeId}
import scorex.core.consensus.SyncInfo
import scorex.core.network.message.SyncInfoMessageSpec
import scorex.core.serialization.Serializer

import scala.util.Try


/**
  * Stores up to 50 last PoW & Pos blocks
  * Thus maximum message size is about 100 * 33 ~= 3.2 KB
  */
case class HybridSyncInfo(override val answer: Boolean,
                          lastPowBlockIds: Seq[ModifierId],
                          lastPosBlockId: ModifierId
                         ) extends SyncInfo {
  import HybridSyncInfo.MaxLastPowBlocks

  require(lastPowBlockIds.size <= MaxLastPowBlocks)

  override def startingPoints: Seq[(ModifierTypeId, ModifierId)] =
    Seq(lastPowBlockIds.map(b => PowBlock.ModifierTypeId -> b) ++ Seq(PosBlock.ModifierTypeId -> lastPosBlockId)).flatten


  override type M = HybridSyncInfo

  override def serializer: Serializer[HybridSyncInfo] = HybridSyncInfoSerializer
}

object HybridSyncInfo {
  val MaxLastPowBlocks = 50 //don't make it more than 127 without changing serialization!
}

object HybridSyncInfoSerializer extends Serializer[HybridSyncInfo] {
   import HybridSyncInfo.MaxLastPowBlocks

  override def toBytes(obj: HybridSyncInfo): Array[Byte] =
    Array(
      if (obj.answer) 1: Byte else 0: Byte,
      obj.lastPowBlockIds.size.toByte
    ) ++ obj.lastPowBlockIds.reduce(_ ++ _) ++ obj.lastPosBlockId

  override def parseBytes(bytes: Array[Byte]): Try[HybridSyncInfo] = Try {
    val answer: Boolean = if (bytes.head == 1.toByte) true else false
    val lastPowBlockIdsSize = bytes.slice(1, 2).head

    assert(lastPowBlockIdsSize >= 0 && lastPowBlockIdsSize <= MaxLastPowBlocks) //todo: or just > 0?
    assert(bytes.length == 2 + (lastPowBlockIdsSize + 1) * NodeViewModifier.ModifierIdSize)

    val lastPowBlockIds = bytes.slice(2,  2 + NodeViewModifier.ModifierIdSize * lastPowBlockIdsSize)
      .grouped(NodeViewModifier.ModifierIdSize).toSeq

    val lastPosBlockId = bytes.slice(2 + NodeViewModifier.ModifierIdSize * lastPowBlockIdsSize, bytes.length)

    HybridSyncInfo(answer, lastPowBlockIds, lastPosBlockId)
  }
}

object HybridSyncInfoMessageSpec extends SyncInfoMessageSpec[HybridSyncInfo](HybridSyncInfoSerializer.parseBytes)