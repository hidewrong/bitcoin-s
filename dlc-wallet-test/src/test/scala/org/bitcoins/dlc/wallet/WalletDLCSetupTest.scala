package org.bitcoins.dlc.wallet

import org.bitcoins.core.currency._
import org.bitcoins.core.number.{UInt32, UInt64}
import org.bitcoins.core.protocol.BitcoinAddress
import org.bitcoins.core.protocol.dlc.models.DLCMessage._
import org.bitcoins.core.protocol.dlc.models._
import org.bitcoins.core.protocol.script.P2WPKHWitnessV0
import org.bitcoins.core.protocol.tlv._
import org.bitcoins.core.wallet.fee.SatoshisPerVirtualByte
import org.bitcoins.core.wallet.utxo.TxoState
import org.bitcoins.crypto._
import org.bitcoins.dlc.wallet.DLCWallet.{
  DuplicateOfferException,
  InvalidAnnouncementSignature
}
import org.bitcoins.dlc.wallet.internal.DLCDataManagement
import org.bitcoins.testkit.wallet.DLCWalletUtil._
import org.bitcoins.testkit.wallet.FundWalletUtil.FundedDLCWallet
import org.bitcoins.testkit.wallet.{BitcoinSDualWalletTest, DLCWalletUtil}
import org.scalatest.{Assertion, FutureOutcome}

import java.net.InetSocketAddress
import scala.concurrent.Future
import scala.reflect.ClassTag

class WalletDLCSetupTest extends BitcoinSDualWalletTest {
  type FixtureParam = (FundedDLCWallet, FundedDLCWallet)

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    withDualFundedDLCWallets(test)
  }

  behavior of "DLCWallet"

  def testNegotiate(
      fundedDLCWallets: (FundedDLCWallet, FundedDLCWallet),
      offerData: DLCOffer
  ): Future[Assertion] = {
    val walletA = fundedDLCWallets._1.wallet
    val walletB = fundedDLCWallets._2.wallet
    val walletADLCManagement = DLCDataManagement(walletA.dlcWalletDAOs)
    val walletBDLCManagement = DLCDataManagement(walletB.dlcWalletDAOs)
    for {
      offer <- walletA.createDLCOffer(
        offerData.contractInfo,
        offerData.collateral,
        Some(offerData.feeRate),
        offerData.timeouts.contractMaturity.toUInt32,
        offerData.timeouts.contractTimeout.toUInt32,
        None,
        None,
        None
      )
      dlcId = calcDLCId(offer.fundingInputs.map(_.outPoint))
      dlcA1Opt <- walletA.dlcDAO.read(dlcId)
      find1 <- walletA.findDLC(dlcId)
      _ = {
        assert(find1.isDefined)
        assert(dlcA1Opt.get.state == DLCState.Offered)
        assert(offer.oracleInfos == offerData.oracleInfos)
        assert(offer.contractInfo == offerData.contractInfo)
        assert(offer.collateral == offerData.collateral)
        assert(offer.feeRate == offerData.feeRate)
        assert(offer.timeouts == offerData.timeouts)
        assert(offer.fundingInputs.nonEmpty)
        assert(offer.changeAddress.value.nonEmpty)
      }

      accept <- walletB.acceptDLCOffer(offer, None, None, None)
      dlcB1Opt <- walletB.dlcDAO.read(dlcId)
      _ = {
        assert(dlcB1Opt.isDefined)
        assert(dlcB1Opt.get.state == DLCState.Accepted)
        assert(accept.fundingInputs.nonEmpty)
        assert(
          accept.fundingInputs
            .map(_.output.value)
            .sum >= accept.collateral
        )
        assert(
          accept.collateral == offer.contractInfo.totalCollateral - offer.collateral
        )
        assert(accept.changeAddress.value.nonEmpty)
      }

      sign <- walletA.signDLC(accept)
      dlcA2Opt <- walletA.dlcDAO.read(dlcId)
      _ = {
        assert(dlcA2Opt.isDefined)
        assert(dlcA2Opt.get.state == DLCState.Signed)
        assert(sign.fundingSigs.length == offer.fundingInputs.size)
      }

      dlcDb <- walletB.addDLCSigs(sign)
      _ = assert(dlcDb.state == DLCState.Signed)
      outcomeSigs <- walletB.dlcSigsDAO.findByDLCId(dlcId)

      refundSigsA <- walletA.dlcRefundSigDAO.findByDLCId(dlcId)
      refundSigsB <- walletB.dlcRefundSigDAO.findByDLCId(dlcId)

      walletAChange <- walletA.addressDAO.read(offer.changeAddress)
      walletAFinal <- walletA.addressDAO.read(offer.pubKeys.payoutAddress)

      walletBChange <- walletB.addressDAO.read(accept.changeAddress)
      walletBFinal <- walletB.addressDAO.read(accept.pubKeys.payoutAddress)

      (announcementsA, announcementDataA, nonceDbsA) <- walletADLCManagement
        .getDLCAnnouncementDbs(dlcDb.dlcId)
      announcementTLVsA = walletADLCManagement.getOracleAnnouncements(
        announcementsA,
        announcementDataA,
        nonceDbsA
      )

      (announcementsB, announcementDataB, nonceDbsB) <- walletBDLCManagement
        .getDLCAnnouncementDbs(dlcDb.dlcId)
      announcementTLVsB = walletBDLCManagement.getOracleAnnouncements(
        announcementsB,
        announcementDataB,
        nonceDbsB
      )
    } yield {
      assert(dlcDb.contractIdOpt.get == sign.contractId)

      assert(refundSigsA.isDefined)
      assert(refundSigsB.isDefined)
      assert(refundSigsA.get.initiatorSig.isDefined)
      assert(refundSigsA.get.initiatorSig == refundSigsB.get.initiatorSig)
      assert(refundSigsA.get.accepterSig == refundSigsB.get.accepterSig)

      val inOutcomeSigs =
        outcomeSigs.map(dbSig => (dbSig.sigPoint, dbSig.initiatorSig.get)).toSet

      assert(sign.cetSigs.outcomeSigs.forall(inOutcomeSigs))

      assert(announcementTLVsA == announcementTLVsB)

      // Test that the Addresses are in the wallet's database
      assert(walletAChange.isDefined)
      assert(walletAFinal.isDefined)
      assert(walletBChange.isDefined)
      assert(walletBFinal.isDefined)
    }
  }

  it must "correctly negotiate a dlc" in {
    (fundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      testNegotiate(fundedDLCWallets, DLCWalletUtil.sampleDLCOffer)
  }

  it must "correctly negotiate a non winner take all dlc" in {
    (fundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      testNegotiate(
        fundedDLCWallets,
        DLCWalletUtil.sampleDLCOfferNonWinnerTakeAll
      )
  }

  it must "correctly negotiate a dlc with a multi-nonce oracle info" in {
    (fundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      testNegotiate(fundedDLCWallets, DLCWalletUtil.sampleMultiNonceDLCOffer)
  }

  // This could happen inputs can end up in different orders when
  // using postgres or using different coin selection algos
  it must "correctly negotiate a dlc with reordered inputs" in {
    (fundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      // construct a contract info that uses many inputs
      val totalCol = Bitcoins(11).satoshis
      val col = totalCol / Satoshis.two

      val outcomes: Vector[(EnumOutcome, Satoshis)] =
        Vector(
          EnumOutcome(winStr) -> totalCol,
          EnumOutcome(loseStr) -> Satoshis.zero
        )

      val oraclePair: ContractOraclePair.EnumPair =
        ContractOraclePair.EnumPair(
          EnumContractDescriptor(outcomes),
          sampleOracleInfo
        )

      val contractInfo: ContractInfo = SingleContractInfo(totalCol, oraclePair)

      val offerData =
        sampleDLCOffer.copy(
          contractInfo = contractInfo,
          collateral = col.satoshis
        )

      val walletA = fundedDLCWallets._1.wallet
      val walletB = fundedDLCWallets._2.wallet

      def reorderInputDbs(
          wallet: DLCWallet,
          dlcId: Sha256Digest
      ): Future[Unit] = {
        for {
          inputDbs <- wallet.dlcInputsDAO.findByDLCId(dlcId)
          _ <- wallet.dlcInputsDAO.deleteByDLCId(dlcId)
          _ <- wallet.dlcInputsDAO.createAll(inputDbs.reverse)
        } yield ()
      }

      for {
        offer <- walletA.createDLCOffer(
          offerData.contractInfo,
          offerData.collateral,
          Some(offerData.feeRate),
          offerData.timeouts.contractMaturity.toUInt32,
          offerData.timeouts.contractTimeout.toUInt32,
          None,
          None,
          None
        )
        dlcId = calcDLCId(offer.fundingInputs.map(_.outPoint))

        accept <- walletB.acceptDLCOffer(offer, None, None, None)

        // reorder dlc inputs in wallets
        _ <- reorderInputDbs(walletA, dlcId)
        _ <- reorderInputDbs(walletB, dlcId)

        sign <- walletA.signDLC(accept)

        dlcDb <- walletB.addDLCSigs(sign)
      } yield assert(dlcDb.state == DLCState.Signed)
  }

  // This could happen inputs can end up in different orders when
  // using postgres or using different coin selection algos
  it must "correctly negotiate a dlc with tlvs & with reordered inputs" in {
    (fundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      // construct a contract info that uses many inputs
      val totalCol = Bitcoins(11).satoshis
      val col = totalCol / Satoshis(2)

      val outcomes: Vector[(EnumOutcome, Satoshis)] =
        Vector(
          EnumOutcome(winStr) -> totalCol,
          EnumOutcome(loseStr) -> Satoshis.zero
        )

      val oraclePair: ContractOraclePair.EnumPair =
        ContractOraclePair.EnumPair(
          EnumContractDescriptor(outcomes),
          sampleOracleInfo
        )

      val contractInfo: ContractInfo = SingleContractInfo(totalCol, oraclePair)

      val offerData =
        sampleDLCOffer.copy(
          contractInfo = contractInfo,
          collateral = col.satoshis
        )

      val walletA = fundedDLCWallets._1.wallet
      val walletB = fundedDLCWallets._2.wallet

      def reorderInputDbs(
          wallet: DLCWallet,
          dlcId: Sha256Digest
      ): Future[Unit] = {
        for {
          inputDbs <- wallet.dlcInputsDAO.findByDLCId(dlcId)
          _ <- wallet.dlcInputsDAO.deleteByDLCId(dlcId)
          _ <- wallet.dlcInputsDAO.createAll(inputDbs.reverse)
        } yield ()
      }

      for {
        offer <- walletA.createDLCOffer(
          offerData.contractInfo,
          offerData.collateral,
          Some(offerData.feeRate),
          offerData.timeouts.contractMaturity.toUInt32,
          offerData.timeouts.contractTimeout.toUInt32,
          None,
          None,
          None
        )
        dlcId = calcDLCId(offer.fundingInputs.map(_.outPoint))

        accept <- walletB.acceptDLCOffer(offer.toTLV, None, None, None)

        // reorder dlc inputs in wallets
        _ <- reorderInputDbs(walletA, dlcId)
        _ <- reorderInputDbs(walletB, dlcId)

        sign <- walletA.signDLC(accept.toTLV)

        dlcDb <- walletB.addDLCSigs(sign.toTLV)
      } yield assert(dlcDb.state == DLCState.Signed)
  }

  it must "correctly negotiate a dlc using TLVs" in {
    (fundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val walletA = fundedDLCWallets._1.wallet
      val walletB = fundedDLCWallets._2.wallet

      val offerData = DLCWalletUtil.sampleDLCOffer

      for {
        offer <- walletA.createDLCOffer(
          offerData.contractInfo.toTLV,
          offerData.collateral,
          Some(offerData.feeRate),
          offerData.timeouts.contractMaturity.toUInt32,
          offerData.timeouts.contractTimeout.toUInt32,
          None,
          None,
          None
        )
        dlcId = calcDLCId(offer.fundingInputs.map(_.outPoint))
        dlcA1Opt <- walletA.dlcDAO.read(dlcId)
        _ = {
          assert(dlcA1Opt.isDefined)
          assert(dlcA1Opt.get.state == DLCState.Offered)
          assert(offer.oracleInfos == offerData.oracleInfos)
          assert(offer.contractInfo == offerData.contractInfo)
          assert(offer.collateral == offerData.collateral)
          assert(offer.feeRate == offerData.feeRate)
          assert(offer.timeouts == offerData.timeouts)
          assert(offer.fundingInputs.nonEmpty)
          assert(offer.changeAddress.value.nonEmpty)
        }

        accept <- walletB.acceptDLCOffer(offer.toTLV, None, None, None)
        dlcB1Opt <- walletB.dlcDAO.read(dlcId)
        _ = {
          assert(dlcB1Opt.isDefined)
          assert(dlcB1Opt.get.state == DLCState.Accepted)
          assert(accept.fundingInputs.nonEmpty)
          assert(
            accept.fundingInputs
              .map(_.output.value)
              .sum >= accept.collateral
          )
          assert(
            accept.collateral == offer.contractInfo.totalCollateral - offer.collateral
          )
          assert(accept.changeAddress.value.nonEmpty)
        }

        sign <- walletA.signDLC(accept.toTLV)
        dlcA2Opt <- walletA.dlcDAO.read(dlcId)
        _ = {
          assert(dlcA2Opt.isDefined)
          assert(dlcA2Opt.get.state == DLCState.Signed)
          assert(sign.fundingSigs.length == offer.fundingInputs.size)
        }

        dlcDb <- walletB.addDLCSigs(sign.toTLV)
        _ = assert(dlcDb.state == DLCState.Signed)
        outcomeSigs <- walletB.dlcSigsDAO.findByDLCId(dlcId)

        refundSigsA <- walletA.dlcRefundSigDAO.findByDLCId(dlcId)
        refundSigsB <- walletB.dlcRefundSigDAO.findByDLCId(dlcId)

        walletAChange <- walletA.addressDAO.read(offer.changeAddress)
        walletAFinal <- walletA.addressDAO.read(offer.pubKeys.payoutAddress)

        walletBChange <- walletB.addressDAO.read(accept.changeAddress)
        walletBFinal <- walletB.addressDAO.read(accept.pubKeys.payoutAddress)

      } yield {
        assert(dlcDb.contractIdOpt.get == sign.contractId)

        assert(refundSigsA.isDefined)
        assert(refundSigsB.isDefined)
        assert(refundSigsA.get.initiatorSig.isDefined)
        assert(refundSigsA.get.initiatorSig == refundSigsB.get.initiatorSig)
        assert(refundSigsA.get.accepterSig == refundSigsB.get.accepterSig)

        assert(sign.cetSigs.outcomeSigs.forall { case (outcome, sig) =>
          outcomeSigs.exists(dbSig =>
            (dbSig.sigPoint, dbSig.initiatorSig.get) == ((outcome, sig)))
        })

        // Test that the Addresses are in the wallet's database
        assert(walletAChange.isDefined)
        assert(walletAFinal.isDefined)
        assert(walletBChange.isDefined)
        assert(walletBFinal.isDefined)
      }
  }

  def getDLCReadyToAddSigs(
      walletA: DLCWallet,
      walletB: DLCWallet,
      offerData: DLCOffer = DLCWalletUtil.sampleDLCOffer
  ): Future[DLCSign] = {
    for {
      accept <- getDLCReadyToSign(walletA, walletB, offerData)
      sign <- walletA.signDLC(accept)
    } yield sign
  }

  def getDLCReadyToSign(
      walletA: DLCWallet,
      walletB: DLCWallet,
      offerData: DLCOffer = DLCWalletUtil.sampleDLCOffer
  ): Future[DLCAccept] = {
    for {
      offer <- walletA.createDLCOffer(
        offerData.contractInfo,
        offerData.collateral,
        Some(offerData.feeRate),
        offerData.timeouts.contractMaturity.toUInt32,
        offerData.timeouts.contractTimeout.toUInt32,
        None,
        None,
        None
      )

      accept <- walletB.acceptDLCOffer(offer, None, None, None)
    } yield accept
  }

  def testDLCSignVerification[E <: Exception](
      walletA: DLCWallet,
      walletB: DLCWallet,
      makeDLCSignInvalid: DLCSign => DLCSign
  )(implicit classTag: ClassTag[E]): Future[Assertion] = {
    val failedAddSigsF = for {
      sign <- getDLCReadyToAddSigs(walletA, walletB)
      invalidSign = makeDLCSignInvalid(sign)
      dlcDb <- walletB.addDLCSigs(invalidSign)
    } yield dlcDb

    recoverToSucceededIf[E](failedAddSigsF)
  }

  def testDLCAcceptVerification(
      walletA: DLCWallet,
      walletB: DLCWallet,
      makeDLCAcceptInvalid: DLCAccept => DLCAccept
  ): Future[Assertion] = {
    val failedAddSigsF = for {
      accept <- getDLCReadyToSign(walletA, walletB)
      invalidSign = makeDLCAcceptInvalid(accept)
      dlcDb <- walletA.signDLC(invalidSign)
    } yield dlcDb

    recoverToSucceededIf[IllegalArgumentException](failedAddSigsF)
  }

  it must "fail to add its own sigs" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val walletA = FundedDLCWallets._1.wallet
      val walletB = FundedDLCWallets._2.wallet

      for {
        sign <- getDLCReadyToAddSigs(walletA, walletB)
        _ <- recoverToSucceededIf[IllegalArgumentException](
          walletA.addDLCSigs(sign)
        )
      } yield succeed
  }

  it must "fail to add dlc funding sigs that do not correspond to the DLC" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val walletA = FundedDLCWallets._1.wallet
      val walletB = FundedDLCWallets._2.wallet

      testDLCSignVerification[IllegalArgumentException](
        walletA,
        walletB,
        (sign: DLCSign) =>
          sign.copy(fundingSigs = DLCWalletUtil.dummyFundingSignatures)
      )
  }

  it must "fail to add dlc funding sigs that are invalid" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val walletA = FundedDLCWallets._1.wallet
      val walletB = FundedDLCWallets._2.wallet

      testDLCSignVerification[IllegalArgumentException](
        walletA,
        walletB,
        (sign: DLCSign) =>
          sign.copy(fundingSigs = FundingSignatures(
            sign.fundingSigs
              .map(_.copy(_2 = P2WPKHWitnessV0(ECPublicKey.freshPublicKey)))
              .toVector
          ))
      )
  }

  it must "fail to add dlc cet sigs that are invalid" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val walletA = FundedDLCWallets._1.wallet
      val walletB = FundedDLCWallets._2.wallet

      testDLCSignVerification[IllegalArgumentException](
        walletA,
        walletB,
        (sign: DLCSign) =>
          sign.copy(cetSigs = CETSignatures(DLCWalletUtil.dummyOutcomeSigs))
      )
  }

  it must "fail to add an invalid dlc refund sig" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val walletA = FundedDLCWallets._1.wallet
      val walletB = FundedDLCWallets._2.wallet

      testDLCSignVerification[IllegalArgumentException](
        walletA,
        walletB,
        (sign: DLCSign) => sign.copy(refundSig = DLCWalletUtil.dummyPartialSig)
      )
  }

  it must "fail to sign dlc with cet sigs that are invalid" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val walletA = FundedDLCWallets._1.wallet
      val walletB = FundedDLCWallets._2.wallet

      testDLCAcceptVerification(
        walletA,
        walletB,
        (accept: DLCAccept) =>
          accept.copy(cetSigs = CETSignatures(DLCWalletUtil.dummyOutcomeSigs))
      )
  }

  it must "fail to sign dlc with an invalid refund sig" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val walletA = FundedDLCWallets._1.wallet
      val walletB = FundedDLCWallets._2.wallet

      testDLCAcceptVerification(
        walletA,
        walletB,
        (accept: DLCAccept) =>
          accept.copy(refundSig = DLCWalletUtil.dummyPartialSig)
      )
  }

  it must "cancel an offered DLC" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val dlcWalletA = FundedDLCWallets._1.wallet
      val walletApiA = dlcWalletA.walletApi

      val offerData: DLCOffer = DLCWalletUtil.sampleDLCOffer

      val announcementTLVs =
        offerData.contractInfo.oracleInfos.head.singleOracleInfos
          .map(_.announcement)
      assert(announcementTLVs.size == 1)
      val announcementTLV = announcementTLVs.head

      for {
        oldBalance <- dlcWalletA.getBalance()
        oldReserved <- walletApiA.utxoHandling.getUtxos(TxoState.Reserved)
        _ = assert(oldReserved.isEmpty)

        offer <- dlcWalletA.createDLCOffer(
          offerData.contractInfo,
          offerData.collateral,
          Some(offerData.feeRate),
          offerData.timeouts.contractMaturity.toUInt32,
          offerData.timeouts.contractTimeout.toUInt32,
          None,
          None,
          None
        )

        dlcId = calcDLCId(offer.fundingInputs.map(_.outPoint))

        _ <- dlcWalletA.cancelDLC(dlcId)

        announcementData <- dlcWalletA.announcementDAO.findByPublicKey(
          announcementTLV.publicKey
        )
        nonceDbs <- dlcWalletA.oracleNonceDAO.findByAnnouncementIds(
          announcementData.map(_.id.get)
        )

        balance <- dlcWalletA.getBalance()
        reserved <- walletApiA.utxoHandling.getUtxos(TxoState.Reserved)
        dlcOpt <- dlcWalletA.findDLC(dlcId)
      } yield {
        assert(balance == oldBalance)
        assert(reserved.isEmpty)
        assert(dlcOpt.isEmpty)

        // Check we persist the announcements
        assert(announcementData.nonEmpty)
        assert(nonceDbs.nonEmpty)
      }
  }

  it must "cancel an accepted DLC" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val dlcWalletA = FundedDLCWallets._1.wallet
      val dlcWalletB = FundedDLCWallets._2.wallet

      val walletApiB = dlcWalletB.walletApi

      val offerData: DLCOffer = DLCWalletUtil.sampleDLCOffer

      for {
        oldBalance <- dlcWalletB.getBalance()
        oldReserved <- walletApiB.utxoHandling.getUtxos(TxoState.Reserved)
        _ = assert(oldReserved.isEmpty)

        offer <- dlcWalletA.createDLCOffer(
          offerData.contractInfo,
          offerData.collateral,
          Some(offerData.feeRate),
          offerData.timeouts.contractMaturity.toUInt32,
          offerData.timeouts.contractTimeout.toUInt32,
          None,
          None,
          None
        )
        _ <- dlcWalletB.acceptDLCOffer(offer, None, None, None)

        dlcId = calcDLCId(offer.fundingInputs.map(_.outPoint))

        _ <- dlcWalletB.cancelDLC(dlcId)

        balance <- dlcWalletB.getBalance()
        reserved <- walletApiB.utxoHandling.getUtxos(TxoState.Reserved)
        dlcOpt <- dlcWalletB.findDLC(dlcId)
      } yield {
        assert(balance == oldBalance)
        assert(reserved.isEmpty)
        assert(dlcOpt.isEmpty)
      }
  }

  it must "cancel a signed DLC" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val dlcWalletA = FundedDLCWallets._1.wallet
      val dlcWalletB = FundedDLCWallets._2.wallet

      val walletApiA = dlcWalletA.walletApi
      val walletApiB = dlcWalletB.walletApi

      val offerData: DLCOffer = DLCWalletUtil.sampleDLCOffer

      for {
        oldBalanceA <- dlcWalletA.getBalance()
        oldReservedA <- walletApiA.utxoHandling.getUtxos(
          TxoState.Reserved
        )
        _ = assert(oldReservedA.isEmpty)

        oldBalanceB <- dlcWalletB.getBalance()
        oldReservedB <- walletApiB.utxoHandling.getUtxos(
          TxoState.Reserved
        )
        _ = assert(oldReservedB.isEmpty)

        offer <- dlcWalletA.createDLCOffer(
          offerData.contractInfo,
          offerData.collateral,
          Some(offerData.feeRate),
          offerData.timeouts.contractMaturity.toUInt32,
          offerData.timeouts.contractTimeout.toUInt32,
          None,
          None,
          None
        )
        accept <- dlcWalletB.acceptDLCOffer(offer, None, None, None)
        sign <- dlcWalletA.signDLC(accept)
        _ <- dlcWalletB.addDLCSigs(sign)

        dlcId = calcDLCId(offer.fundingInputs.map(_.outPoint))

        _ <- dlcWalletA.cancelDLC(dlcId)
        _ <- dlcWalletB.cancelDLC(dlcId)

        balanceA <- dlcWalletA.getBalance()
        reservedA <- walletApiA.utxoHandling.getUtxos(TxoState.Reserved)
        dlcAOpt <- dlcWalletA.findDLC(dlcId)

        balanceB <- dlcWalletB.getBalance()
        reservedB <- walletApiB.utxoHandling.getUtxos(TxoState.Reserved)
        dlcBOpt <- dlcWalletB.findDLC(dlcId)
      } yield {
        assert(balanceA == oldBalanceA)
        assert(reservedA.isEmpty)
        assert(dlcAOpt.isEmpty)

        assert(balanceB == oldBalanceB)
        assert(reservedB.isEmpty)
        assert(dlcBOpt.isEmpty)
      }
  }

  it must "fail to cancel a broadcasted DLC" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val walletA = FundedDLCWallets._1.wallet
      val walletB = FundedDLCWallets._2.wallet

      val offerData: DLCOffer = DLCWalletUtil.sampleDLCOffer

      for {
        offer <- walletA.createDLCOffer(
          offerData.contractInfo,
          offerData.collateral,
          Some(offerData.feeRate),
          offerData.timeouts.contractMaturity.toUInt32,
          offerData.timeouts.contractTimeout.toUInt32,
          None,
          None,
          None
        )
        accept <- walletB.acceptDLCOffer(offer, None, None, None)
        sign <- walletA.signDLC(accept)
        _ <- walletB.addDLCSigs(sign)

        tx <- walletB.broadcastDLCFundingTx(sign.contractId)
        // make sure other wallet sees it
        _ <- walletA.transactionProcessing.processTransaction(tx, None)

        dlcId = calcDLCId(offer.fundingInputs.map(_.outPoint))

        _ <- recoverToSucceededIf[IllegalArgumentException](
          walletA.cancelDLC(dlcId)
        )

        _ <- recoverToSucceededIf[IllegalArgumentException](
          walletB.cancelDLC(dlcId)
        )
      } yield succeed
  }

  it must "fail to refund a DLC that hasn't reached its timeout" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val walletA = FundedDLCWallets._1.wallet
      val walletB = FundedDLCWallets._2.wallet

      val offerData: DLCOffer = DLCWalletUtil.sampleDLCOffer

      for {
        offer <- walletA.createDLCOffer(
          offerData.contractInfo,
          offerData.collateral,
          Some(offerData.feeRate),
          offerData.timeouts.contractMaturity.toUInt32,
          UInt32.max,
          None,
          None,
          None
        )
        accept <- walletB.acceptDLCOffer(offer, None, None, None)
        sign <- walletA.signDLC(accept)
        _ <- walletB.addDLCSigs(sign)

        tx <- walletB.broadcastDLCFundingTx(sign.contractId)
        // make sure other wallet sees it
        _ <- walletA.transactionProcessing.processTransaction(tx, None)

        dlcId = calcDLCId(offer.fundingInputs.map(_.outPoint))

        _ <- recoverToSucceededIf[IllegalArgumentException](
          walletA.executeDLCRefund(sign.contractId)
        )

        _ <- recoverToSucceededIf[IllegalArgumentException](
          walletB.executeDLCRefund(sign.contractId)
        )
      } yield succeed
  }

  it must "setup and execute with oracle example" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val walletA = FundedDLCWallets._1.wallet
      val walletB = FundedDLCWallets._2.wallet

      val winStr = "Democrat_win"
      val loseStr = "Republican_win"
      val drawStr = "other"

      val betSize = 10000

      val contractDescriptor: EnumContractDescriptor =
        EnumContractDescriptor.fromStringVec(
          Vector(
            winStr -> Satoshis(betSize),
            loseStr -> Satoshis.zero,
            drawStr -> Satoshis(betSize / 2)
          )
        )

      val oracleInfo = EnumSingleOracleInfo(
        OracleAnnouncementTLV(
          "fdd824b4caaec7479cc9d37003f5add6504d035054ffeac8637a990305a45cfecc1062044c3f68b45318f57e41c4544a4a950c0744e2a80854349a3426b00ad86da5090b9e942dc6df2ae87f007b45b0ccd63e6c354d92c4545fc099ea3e137e54492d1efdd822500001a6a09c7c83c50b34f9db560a2e14fef2eab5224c15b18c7114331756364bfce65ffe3800fdd8062400030c44656d6f637261745f77696e0e52657075626c6963616e5f77696e056f746865720161"
        )
      )

      val offerData = DLCOffer(
        DLCOfferTLV.currentVersionOpt,
        SingleContractInfo(contractDescriptor, oracleInfo),
        dummyDLCKeys,
        Satoshis(5000),
        Vector(dummyFundingInputs.head),
        dummyAddress,
        payoutSerialId = UInt64.zero,
        changeSerialId = UInt64.one,
        fundOutputSerialId = UInt64.max,
        SatoshisPerVirtualByte(Satoshis(3)),
        dummyTimeouts
      )

      val oracleSig = SchnorrDigitalSignature(
        "a6a09c7c83c50b34f9db560a2e14fef2eab5224c15b18c7114331756364bfce6c59736cdcfe1e0a89064f846d5dbde0902f82688dde34dc1833965a60240f287"
      )

      val sig =
        OracleSignatures(oracleInfo, Vector(oracleSig))

      for {
        offer <- walletA.createDLCOffer(
          offerData.contractInfo,
          offerData.collateral,
          Some(offerData.feeRate),
          offerData.timeouts.contractMaturity.toUInt32,
          offerData.timeouts.contractTimeout.toUInt32,
          None,
          None,
          None
        )
        _ = {
          assert(offer.oracleInfos == offerData.oracleInfos)
          assert(offer.contractInfo == offerData.contractInfo)
          assert(offer.collateral == offerData.collateral)
          assert(offer.feeRate == offerData.feeRate)
          assert(offer.timeouts == offerData.timeouts)
          assert(offer.fundingInputs.nonEmpty)
          assert(offer.changeAddress.value.nonEmpty)
        }

        dlcId = calcDLCId(offer.fundingInputs.map(_.outPoint))

        accept <- walletB.acceptDLCOffer(offer, None, None, None)
        _ = {
          assert(accept.fundingInputs.nonEmpty)
          assert(
            accept.collateral == offer.contractInfo.maxOffererPayout - offer.collateral
          )
          assert(accept.changeAddress.value.nonEmpty)
        }

        sign <- walletA.signDLC(accept)
        _ = {
          assert(sign.fundingSigs.length == offerData.fundingInputs.size)
        }

        dlcDb <- walletB.addDLCSigs(sign)
        outcomeSigs <- walletB.dlcSigsDAO.findByDLCId(dlcId)

        refundSigsA <- walletA.dlcRefundSigDAO.findByDLCId(dlcId)
        refundSigsB <- walletB.dlcRefundSigDAO.findByDLCId(dlcId)

        walletAChange <- walletA.addressDAO.read(offer.changeAddress)
        walletAFinal <- walletA.addressDAO.read(offer.pubKeys.payoutAddress)

        walletBChange <- walletB.addressDAO.read(accept.changeAddress)
        walletBFinal <- walletB.addressDAO.read(accept.pubKeys.payoutAddress)

        _ = {
          assert(dlcDb.contractIdOpt.get == sign.contractId)

          assert(refundSigsA.isDefined)
          assert(refundSigsB.isDefined)
          assert(refundSigsA.get.initiatorSig.isDefined)
          assert(refundSigsA.get.initiatorSig == refundSigsB.get.initiatorSig)
          assert(refundSigsA.get.accepterSig == refundSigsB.get.accepterSig)

          assert(sign.cetSigs.outcomeSigs.forall { case (outcome, sig) =>
            outcomeSigs.exists(dbSig =>
              (dbSig.sigPoint, dbSig.initiatorSig.get) == ((outcome, sig)))
          })

          // Test that the Addresses are in the wallet's database
          assert(walletAChange.isDefined)
          assert(walletAFinal.isDefined)
          assert(walletBChange.isDefined)
          assert(walletBFinal.isDefined)
        }

        tx <- walletB.broadcastDLCFundingTx(sign.contractId)
        _ <- walletA.transactionProcessing.processTransaction(tx, None)

        func = (wallet: DLCWallet) =>
          wallet.executeDLC(sign.contractId, sig).map(_.get)
        result <- dlcExecutionTest(
          dlcA = walletA,
          dlcB = walletB,
          asInitiator = true,
          func = func,
          expectedOutputs = 1
        )
      } yield {
        assert(result)
      }
  }

  it must "accept 2 offers with the same oracle info" in { wallets =>
    val walletA = wallets._1.wallet
    val walletB = wallets._2.wallet

    // https://test.oracle.suredbits.com/contract/enum/75b08299654dca23b80cf359db6afb6cfd6e55bc898b5397d3c0fe796dfc13f0/12fb3e5f091086329ed0d2a12c3fcfa80111a36ef3fc1ac9c2567076a57d6a73
    val contractInfoA = ContractInfoV0TLV.fromHex(
      "fdd82eeb00000000000186a0fda71026030359455300000000000186a0024e4f0000000000000000056f746865720000000000000000fda712b5fdd824b1596ec40d0dae3fdf54d9795ad51ec069970c6863a02d244663d39fd6bedadc0070349e1ba2e17583ee2d1cb3ae6fffaaa1c45039b61c5c4f1d0d864221c461745d1bcfab252c6dd9edd7aea4c5eeeef138f7ff7346061ea40143a9f5ae80baa9fdd8224d0001fa5b84283852400b21a840d5d5ca1cc31867c37326ad521aa50bebf3df4eea1a60b03280fdd8060f000303594553024e4f056f74686572135465746865722d52657365727665732d363342"
    )
    // https://test.oracle.suredbits.com/contract/enum/75b08299654dca23b80cf359db6afb6cfd6e55bc898b5397d3c0fe796dfc13f0/e5fb1dd68e51f5d735a0dd83ff88795bd7c959003a01e16c1ad08df3758de057
    val contractInfoB = ContractInfoV0TLV.fromHex(
      "fdd82eeb0000000000002710fda7102603035945530000000000000000024e4f0000000000002710056f746865720000000000000000fda712b5fdd824b1596ec40d0dae3fdf54d9795ad51ec069970c6863a02d244663d39fd6bedadc0070349e1ba2e17583ee2d1cb3ae6fffaaa1c45039b61c5c4f1d0d864221c461745d1bcfab252c6dd9edd7aea4c5eeeef138f7ff7346061ea40143a9f5ae80baa9fdd8224d0001fa5b84283852400b21a840d5d5ca1cc31867c37326ad521aa50bebf3df4eea1a60b03280fdd8060f000303594553024e4f056f74686572135465746865722d52657365727665732d363342"
    )

    assert(contractInfoA.oracleInfo == contractInfoB.oracleInfo)

    val feeRateOpt = Some(SatoshisPerVirtualByte(Satoshis.one))
    val totalCollateral = Satoshis(5000)

    def makeOffer(contractInfo: ContractInfoV0TLV): Future[DLCOffer] = {
      walletA.createDLCOffer(
        contractInfoTLV = contractInfo,
        collateral = totalCollateral,
        feeRateOpt = feeRateOpt,
        locktime = UInt32.zero,
        refundLT = UInt32.one,
        peerAddressOpt = None,
        externalPayoutAddressOpt = None,
        externalChangeAddressOpt = None
      )
    }

    for {
      offerA <- makeOffer(contractInfoA)
      offerB <- makeOffer(contractInfoB)

      _ <- walletB.acceptDLCOffer(offerA, None, None, None)
      _ <- walletB.acceptDLCOffer(offerB, None, None, None)
    } yield succeed
  }

  private val numericContractInfo =
    DLCWalletUtil.numericContractInfoV0

  it must "fail accepting an offer twice simultaneously" in { wallets =>
    val walletA = wallets._1.wallet
    val walletB = wallets._2.wallet

    val contractInfoA = numericContractInfo
    val feeRateOpt = Some(SatoshisPerVirtualByte(Satoshis.one))
    val totalCollateral = Satoshis(100000)

    def makeOffer(contractInfo: ContractInfoV0TLV): Future[DLCOffer] = {
      walletA.createDLCOffer(
        contractInfoTLV = contractInfo,
        collateral = (totalCollateral / Satoshis.two).satoshis,
        feeRateOpt = feeRateOpt,
        locktime = UInt32.zero,
        refundLT = UInt32.one,
        peerAddressOpt = None,
        externalPayoutAddressOpt = None,
        externalChangeAddressOpt = None
      )
    }

    for {
      offer <- makeOffer(contractInfoA)
      accept1F = walletB.acceptDLCOffer(offer, None, None, None)
      accept2F = walletB.acceptDLCOffer(offer, None, None, None)
      _ <- recoverToSucceededIf[DuplicateOfferException](
        Future.sequence(Seq(accept1F, accept2F))
      )
    } yield {
      succeed
    }
  }

  it must "accept an offer twice sequentially" in { wallets =>
    val walletA = wallets._1.wallet
    val walletB = wallets._2.wallet

    val contractInfoA = numericContractInfo
    val feeRateOpt = Some(SatoshisPerVirtualByte(Satoshis.one))
    val totalCollateral = Satoshis(100000)

    def makeOffer(contractInfo: ContractInfoV0TLV): Future[DLCOffer] = {
      walletA.createDLCOffer(
        contractInfoTLV = contractInfo,
        collateral = (totalCollateral / Satoshis.two).satoshis,
        feeRateOpt = feeRateOpt,
        locktime = UInt32.zero,
        refundLT = UInt32.one,
        peerAddressOpt = None,
        externalPayoutAddressOpt = None,
        externalChangeAddressOpt = None
      )
    }

    for {
      offer <- makeOffer(contractInfoA)
      accept1 <- walletB.acceptDLCOffer(offer, None, None, None)
      accept2 <- walletB.acceptDLCOffer(offer, None, None, None)
    } yield {
      assert(accept1 == accept2)
    }
  }

  it must "not be able to sign its own accept" in { wallets =>
    val walletA = wallets._1.wallet
    val walletB = wallets._2.wallet

    val offerData: DLCOffer = DLCWalletUtil.sampleDLCOffer

    for {
      offer <- walletA.createDLCOffer(
        offerData.contractInfo,
        offerData.collateral,
        Some(offerData.feeRate),
        offerData.timeouts.contractMaturity.toUInt32,
        UInt32.max,
        None,
        None,
        None
      )
      accept <- walletB.acceptDLCOffer(offer, None, None, None)
      res <- recoverToSucceededIf[IllegalArgumentException](
        walletB.signDLC(accept)
      )
    } yield res
  }

  it must "fail to accept an offer when you do not have enough money in the wallet" in {
    wallets =>
      val walletA = wallets._1.wallet
      val walletB = wallets._2.wallet

      val offerData: DLCOffer =
        DLCWalletUtil.buildDLCOffer(totalCollateral = Bitcoins(100))

      for {
        offer <- walletA.createDLCOffer(
          contractInfo = offerData.contractInfo,
          collateral = offerData.collateral,
          feeRateOpt = Some(offerData.feeRate),
          locktime = offerData.timeouts.contractMaturity.toUInt32,
          refundLocktime = UInt32.max,
          peerAddressOpt = None,
          externalPayoutAddressOpt = None,
          externalChangeAddressOpt = None
        )
        _ <- recoverToSucceededIf[RuntimeException](
          walletB.acceptDLCOffer(offer, None, None, None)
        )
      } yield succeed
  }

  it must "fail to create an offer with an invalid announcement signature" in {
    wallets =>
      val walletA = wallets._1.wallet

      val offerData: DLCOffer = DLCWalletUtil.invalidDLCOffer

      for {
        res <- recoverToSucceededIf[InvalidAnnouncementSignature](
          walletA.createDLCOffer(
            offerData.contractInfo,
            offerData.collateral,
            Some(offerData.feeRate),
            offerData.timeouts.contractMaturity.toUInt32,
            UInt32.max,
            None,
            None,
            None
          )
        )
      } yield {
        res
      }
  }

  it must "fail to accept an offer with an invalid announcement signature" in {
    wallets =>
      val walletA = wallets._1.wallet
      val walletB = wallets._2.wallet

      // https://test.oracle.suredbits.com/contract/enum/75b08299654dca23b80cf359db6afb6cfd6e55bc898b5397d3c0fe796dfc13f0/12fb3e5f091086329ed0d2a12c3fcfa80111a36ef3fc1ac9c2567076a57d6a73
      val contractInfo = ContractInfoV0TLV.fromHex(
        "fdd82eeb00000000000186a0fda71026030359455300000000000186a0024e4f0000000000000000056f746865720000000000000000fda712b5fdd824b1596ec40d0dae3fdf54d9795ad51ec069970c6863a02d244663d39fd6bedadc0070349e1ba2e17583ee2d1cb3ae6fffaaa1c45039b61c5c4f1d0d864221c461745d1bcfab252c6dd9edd7aea4c5eeeef138f7ff7346061ea40143a9f5ae80baa9fdd8224d0001fa5b84283852400b21a840d5d5ca1cc31867c37326ad521aa50bebf3df4eea1a60b03280fdd8060f000303594553024e4f056f74686572135465746865722d52657365727665732d363342"
      )
      val feeRateOpt = Some(SatoshisPerVirtualByte(Satoshis.one))
      val totalCollateral = Satoshis(5000)

      for {
        offer <- walletA.createDLCOffer(
          contractInfoTLV = contractInfo,
          collateral = totalCollateral,
          feeRateOpt = feeRateOpt,
          locktime = UInt32.zero,
          refundLT = UInt32.one,
          peerAddressOpt = None,
          externalPayoutAddressOpt = None,
          externalChangeAddressOpt = None
        )
        invalidOffer = offer.copy(contractInfo = invalidContractInfo)
        res <- recoverToSucceededIf[InvalidAnnouncementSignature](
          walletB.acceptDLCOffer(invalidOffer, None, None, None)
        )
      } yield {
        res
      }

  }

  it must "use external payout and change addresses when they are provided" in {
    wallets =>
      val walletA = wallets._1.wallet
      val walletB = wallets._2.wallet

      // https://test.oracle.suredbits.com/contract/enum/75b08299654dca23b80cf359db6afb6cfd6e55bc898b5397d3c0fe796dfc13f0/12fb3e5f091086329ed0d2a12c3fcfa80111a36ef3fc1ac9c2567076a57d6a73
      val contractInfo = ContractInfoV0TLV.fromHex(
        "fdd82eeb00000000000186a0fda71026030359455300000000000186a0024e4f0000000000000000056f746865720000000000000000fda712b5fdd824b1596ec40d0dae3fdf54d9795ad51ec069970c6863a02d244663d39fd6bedadc0070349e1ba2e17583ee2d1cb3ae6fffaaa1c45039b61c5c4f1d0d864221c461745d1bcfab252c6dd9edd7aea4c5eeeef138f7ff7346061ea40143a9f5ae80baa9fdd8224d0001fa5b84283852400b21a840d5d5ca1cc31867c37326ad521aa50bebf3df4eea1a60b03280fdd8060f000303594553024e4f056f74686572135465746865722d52657365727665732d363342"
      )
      val contractInfo1 = DLCWalletUtil.sampleDLCOffer.contractInfo.toTLV

      val feeRateOpt = Some(SatoshisPerVirtualByte(Satoshis.one))
      val totalCollateral = Satoshis(5000)
      val feeRateOpt1 = Some(SatoshisPerVirtualByte(Satoshis.two))
      val totalCollateral1 = Satoshis(10000)

      // random testnet addresses
      val payoutAddressAOpt = Some(
        BitcoinAddress.fromString("tb1qw98mrsxpqtz25xe332khnvlapvl09ejnzk7c3f")
      )
      val changeAddressAOpt = Some(
        BitcoinAddress.fromString("tb1qkfaglsvpcwe5pm9ktqs80u9d9jd0qzgqjqd240")
      )
      val payoutAddressBOpt =
        Some(BitcoinAddress.fromString("2MsM67NLa71fHvTUBqNENW15P68nHB2vVXb"))
      val changeAddressBOpt =
        Some(BitcoinAddress.fromString("2N4YXTxKEso3yeYXNn5h42Vqu3FzTTQ8Lq5"))
      val peerAddressOpt1 =
        Some(InetSocketAddress.createUnresolved("127.0.0.1", 1))
      val peerAddressOpt2 =
        Some(InetSocketAddress.createUnresolved("127.0.0.1", 2))
      val peerAddressOpt3 =
        Some(InetSocketAddress.createUnresolved("127.0.0.1", 3))

      for {
        offer <- walletA.createDLCOffer(
          contractInfoTLV = contractInfo,
          collateral = totalCollateral,
          feeRateOpt = feeRateOpt,
          locktime = UInt32.zero,
          refundLT = UInt32.one,
          peerAddressOpt = peerAddressOpt1,
          externalPayoutAddressOpt = payoutAddressAOpt,
          externalChangeAddressOpt = changeAddressAOpt
        )
        accept <- walletB.acceptDLCOffer(
          offer,
          peerAddressOpt2,
          payoutAddressBOpt,
          changeAddressBOpt
        )
        offer1 <- walletA.createDLCOffer(
          contractInfoTLV = contractInfo1,
          collateral = totalCollateral1,
          feeRateOpt = feeRateOpt1,
          locktime = UInt32.zero,
          refundLT = UInt32.one,
          peerAddressOpt = peerAddressOpt3,
          externalPayoutAddressOpt = None,
          externalChangeAddressOpt = None
        )
        accept1 <- walletB.acceptDLCOffer(offer1, peerAddressOpt3, None, None)
      } yield {
        assert(offer.pubKeys.payoutAddress == payoutAddressAOpt.get)
        assert(offer.changeAddress == changeAddressAOpt.get)
        assert(accept.pubKeys.payoutAddress == payoutAddressBOpt.get)
        assert(accept.changeAddress == changeAddressBOpt.get)
        assert(offer1.pubKeys.payoutAddress != payoutAddressAOpt.get)
        assert(offer1.changeAddress != changeAddressAOpt.get)
        assert(accept1.pubKeys.payoutAddress != payoutAddressBOpt.get)
        assert(accept1.changeAddress != changeAddressBOpt.get)
      }
  }

  it must "setup a DLC and allow re-use of inputs on the accept side" in {
    (FundedDLCWallets: (FundedDLCWallet, FundedDLCWallet)) =>
      val walletA = FundedDLCWallets._1.wallet
      val walletB = FundedDLCWallets._2.wallet
      val offerData: DLCOffer =
        DLCWalletUtil.sampleDLCOffer.copy(
          contractInfo = DLCWalletUtil.sampleContractInfo2,
          collateral = DLCWalletUtil.amt2
        )
      val amt2: Satoshis = Bitcoins(3).satoshis
      val offerCollateral2 = amt2
      lazy val sampleContractInfo2: ContractInfo =
        SingleContractInfo(amt2, sampleContractOraclePair)
      val offerData2 = DLCWalletUtil.sampleDLCOffer
        .copy(contractInfo = sampleContractInfo2, collateral = offerCollateral2)

      for {
        offer1 <- walletA.createDLCOffer(
          offerData.contractInfo,
          offerData.collateral,
          Some(offerData.feeRate),
          offerData.timeouts.contractMaturity.toUInt32,
          offerData.timeouts.contractTimeout.toUInt32,
          None,
          None,
          None
        )
        // accept it for the first time using the inputs
        _ <- walletB.acceptDLCOffer(offer1.toTLV, None, None, None)
        // cancel the offer
        _ <- walletA.cancelDLC(dlcId = offer1.dlcId)

        offer2 <- walletA.createDLCOffer(
          offerData2.contractInfo,
          offerCollateral2,
          Some(offerData2.feeRate),
          offerData2.timeouts.contractMaturity.toUInt32,
          offerData2.timeouts.contractTimeout.toUInt32,
          None,
          None,
          None
        )
        _ <- walletB.acceptDLCOffer(offer2.toTLV, None, None, None)
      } yield succeed
  }
}
