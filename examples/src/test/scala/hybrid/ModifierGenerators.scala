package hybrid

import examples.commons.SimpleBoxTransaction
import examples.curvepos.Nonce
import examples.curvepos.transaction.{PublicKey25519NoncedBox, PublicKey25519NoncedBoxSerializer}
import examples.hybrid.blocks.{HybridBlock, PosBlock, PowBlock, PowBlockCompanion}
import examples.hybrid.history.HybridHistory
import examples.hybrid.state.HBoxStoredState
import io.iohk.iodb.ByteArrayWrapper
import org.scalacheck.Gen
import scorex.core.ModifierId
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PrivateKey25519
import scorex.crypto.hash.Blake2b256
import scorex.testkit.generators.CoreGenerators

trait ModifierGenerators {
  this: HybridGenerators with CoreGenerators =>

  private val hf = Blake2b256

  val txCountGen: Gen[Int] = Gen.chooseNum(0, 50)
  val insPerTxCountGen: Gen[Int] = Gen.chooseNum(1, 20)
  val attachGen: Gen[Array[Byte]] = genBoundedBytes(0, 4096)

  val txGen: Gen[(Int, Int, Array[Byte])] = for {
    tx <- txCountGen
    in <- insPerTxCountGen
    at <- attachGen
  } yield (tx, in, at)

  def validPosBlocks(state: HBoxStoredState, parentIds: Seq[ModifierId]): Seq[PosBlock] = {
    val count = parentIds.size
    val (txCount, insPerTx, attach) = txGen.sample.get

    assert(txCount >= 0 && txCount <= 50)
    assert(insPerTx >= 1 && insPerTx <= 20)

    def filterOutForgedBoxes(in: (ByteArrayWrapper, ByteArrayWrapper)): Boolean = {
      PublicKey25519NoncedBoxSerializer.parseBytes(in._2.data).map(_.value).getOrElse(0L) > 0
    }

    val stateBoxes = state.store.getAll()
      .filter(filterOutForgedBoxes)
      .take(count * txCount * insPerTx + 1)
      .map { case (_, wrappedData) => PublicKey25519NoncedBoxSerializer.parseBytes(wrappedData.data).get }
      .toSeq

    assert(stateBoxes.size == count * txCount * insPerTx + 1)

    val txs = stateBoxes.tail.grouped(insPerTx).map { inputs =>
      val fee = 0
      val from = inputs.map(i => privKey(i.value)._1 -> i.nonce).toIndexedSeq
      val to = inputs.map(i => privKey(i.value)._2 -> i.value).toIndexedSeq
      SimpleBoxTransaction(from, to, fee = fee, System.currentTimeMillis())
    }.toSeq

    txs.foreach {
      _.boxIdsToOpen.foreach { id => assert(state.closedBox(id).isDefined) }
    }

    val txsGrouped = txs.grouped(txs.size / count)

    assert(txsGrouped.size == count)

    val genBox: PublicKey25519NoncedBox = stateBoxes.head
    val generator = privKey(genBox.value)._1
    //val parentId = ModifierId @@ state.version

    txsGrouped.zip(parentIds).map{case (blockTxs, parentId) =>
      PosBlock.create(parentId, System.currentTimeMillis(), blockTxs, genBox, attach, generator)
    }
  }

  def semanticallyValidModifier(state: HBoxStoredState): PosBlock = validPosBlock(state, Seq(state.version))

  def pairCompleted(curHistory: HybridHistory, blocks: Seq[HybridBlock]): Boolean =
    if (blocks.isEmpty) curHistory.pairCompleted
    else blocks.last.isInstanceOf[PosBlock]

  def syntacticallyValidModifier(curHistory: HybridHistory): HybridBlock =
    syntacticallyValidModifier(curHistory, Seq())

  def syntacticallyValidModifier(curHistory: HybridHistory, blocks: Seq[HybridBlock]): HybridBlock = {
    if (pairCompleted(curHistory, blocks)) {
      for {
        timestamp: Long <- positiveLongGen
        nonce: Long <- positiveLongGen
        brothersCount: Byte <- positiveByteGen
        proposition: PublicKey25519Proposition <- propositionGen
        brothers <- Gen.listOfN(brothersCount, powHeaderGen)
      } yield {
        val brotherBytes = PowBlockCompanion.brotherBytes(brothers)
        val brothersHash: Array[Byte] = Blake2b256(brotherBytes)

        val (bestPowId, bestPosId) = blocks.size match {
          case i: Int if i == 0 => curHistory.bestPowId -> curHistory.bestPosId
          case i: Int if i == 1 => curHistory.bestPowId -> blocks.head.id
          case i: Int if i >= 2 => blocks.drop(1).last.id -> blocks.last.id
        }

        new PowBlock(bestPowId, bestPosId, timestamp, nonce, brothersCount, brothersHash, proposition, brothers)
      }
    } else {
      for {
        timestamp: Long <- positiveLongGen
        txs: Seq[SimpleBoxTransaction] <- smallInt.flatMap(txNum => Gen.listOfN(txNum, simpleBoxTransactionGen))
        box: PublicKey25519NoncedBox <- noncedBoxGen
        attach: Array[Byte] <- attachGen
        generator: PrivateKey25519 <- key25519Gen.map(_._1)
        bestPowId = blocks.lastOption.map(_.id).getOrElse(curHistory.bestPowId)
      } yield PosBlock.create(bestPowId, timestamp, txs, box.copy(proposition = generator.publicImage), attach, generator)
    }
  }.sample.get

  def syntacticallyValidModifiers(curHistory: HybridHistory, count: Int): Seq[HybridBlock] =
    (1 to count).foldLeft(Seq[HybridBlock]()) { case (blocks, _) =>
      blocks ++ Seq(syntacticallyValidModifier(curHistory, blocks))
    }

  def syntacticallyInvalidModifier(curHistory: HybridHistory): HybridBlock = {
    syntacticallyValidModifier(curHistory) match {
      case pow: PowBlock => pow.copy(parentId = ModifierId @@ hf(pow.parentId))
      case pos: PosBlock => pos.copy(parentId = ModifierId @@ hf(pos.parentId))
    }
  }

  def semanticallyInvalidModifier(state: HBoxStoredState): PosBlock = {
    val posBlock: PosBlock = semanticallyValidModifier(state)
    posBlock.transactions.lastOption.map { lastTx =>
      val modifiedFrom = (lastTx.from.head._1, Nonce @@ (lastTx.from.head._2 + 1)) +: lastTx.from.tail
      val modifiedLast = lastTx.copy(from = modifiedFrom)
      posBlock.copy(transactions = posBlock.transactions.dropRight(1) :+ modifiedLast)
    }.getOrElse {
      val modifiedGenerator = posBlock.generatorBox.copy(nonce = Nonce @@ (posBlock.generatorBox.nonce + 1))
      posBlock.copy(generatorBox = modifiedGenerator)
    }
  }

  def totallyValidModifier(history: HybridHistory, state: HBoxStoredState): HybridBlock =
    syntacticallyValidModifier(history) match {
      case posSyn: PosBlock =>
        val semBlock = semanticallyValidModifier(state)
        posSyn.copy(transactions = semBlock.transactions)
      case powSyn: PowBlock => powSyn
    }

  def totallyValidModifiers(history: HT, state: ST, count: Int): Seq[HybridBlock] = {
    require(count >= 1)
    val mods = syntacticallyValidModifiers(history, count)

    val filteredIds = mods.filter(_.isInstanceOf[PowBlock]).toBuffer

    if(mods.head.isInstanceOf[PosBlock]) filteredIds.prepend(state.version)
    if(mods.last.asInstanceOf[PowBlock]) filteredIds.remove(filteredIds.size-1)

    assert(filteredIds.size == mods.filter(_.isInstanceOf[PosBlock].size))

    val posBlocks = validPosBlocks(state, filteredIds).toBuffer

    mods.map{_ match =>
      case pw: PowBlock => pw
      case _: PosBlock => posBlocks.head; posBlocks.remove(0)
    }
  }
}