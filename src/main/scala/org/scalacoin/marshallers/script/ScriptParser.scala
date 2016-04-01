package org.scalacoin.marshallers.script

import org.scalacoin.script._
import org.scalacoin.script.constant._
import org.scalacoin.script.crypto.{OP_CHECKMULTISIGVERIFY, OP_CHECKMULTISIG}
import org.scalacoin.util.{Factory, BitcoinSUtil}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/**
 * Created by chris on 1/7/16.
 */
trait ScriptParser extends Factory[List[ScriptToken]] {

  private def logger = LoggerFactory.getLogger(this.getClass)


  /**
   * Parses a list of bytes into a list of script tokens
   * @param bytes
   * @return
   */
  def fromBytes(bytes : Seq[Byte]) : List[ScriptToken] = {
    val scriptTokens : List[ScriptToken] = parse(bytes)
    scriptTokens
  }


  /**
   * Parses an asm output script of a transaction
   * example: "OP_DUP OP_HASH160 e2e7c1ab3f807151e832dd1accb3d4f5d7d19b4b OP_EQUALVERIFY OP_CHECKSIG"
   * example: ["0", "IF 0x50 ENDIF 1", "P2SH,STRICTENC", "0x50 is reserved (ok if not executed)"] (from script_valid.json)
   * @param str
   * @return
   */
  def fromString(str : String) : List[ScriptToken] = {
    val scriptTokens : List[ScriptToken] = parse(str)
    scriptTokens
  }






  /**
   * Parses an asm output script of a transaction
   * example: "OP_DUP OP_HASH160 e2e7c1ab3f807151e832dd1accb3d4f5d7d19b4b OP_EQUALVERIFY OP_CHECKSIG"
   * example: ["0", "IF 0x50 ENDIF 1", "P2SH,STRICTENC", "0x50 is reserved (ok if not executed)"] (from script_valid.json)
   * @param str
   * @return
   */
  private def parse(str : String) : List[ScriptToken] = {
    logger.debug("Parsing string: " + str + " into a list of script tokens")

    @tailrec
    def loop(operations : List[String], accum : List[ScriptToken]) : List[ScriptToken] = {
      logger.debug("Attempting to parse: " + operations.headOption)
      logger.debug("Accum: " + accum)
      operations match {
        //for parsing strings like 'Az', need to remove single quotes
        //example: https://github.com/bitcoin/bitcoin/blob/master/src/test/data/script_valid.json#L24
        case h :: t if (h.size > 0 && h.head == ''' && h.last == ''') =>
          logger.debug("Found a string constant")
          val strippedQuotes = h.replace("'","")
          if (strippedQuotes.size == 0) loop(t, OP_0 :: accum)
          else loop(t, ScriptConstantImpl(BitcoinSUtil.encodeHex(strippedQuotes.getBytes)) :: accum)

        //for the case that we last saw a ByteToPushOntoStack operation
        //this means that the next byte needs to be parsed as a constant
        //not a script operation
        case h :: t if (h.size == 4 && h.substring(0,2) == "0x"
          && accum.headOption.isDefined && accum.head.isInstanceOf[BytesToPushOntoStackImpl]) =>
          logger.debug("Found a script operation preceded by a BytesToPushOntoStackImpl")
          val hexString = h.substring(2,h.size).toLowerCase
          logger.debug("Hex string: " + hexString)
          loop(t,ScriptNumberImpl(BitcoinSUtil.hexToLong(hexString)) :: accum)

        //OP_PUSHDATA operations are always followed by the amount of bytes to be pushed
        //onto the stack
        case h :: t if (h.size > 1 && h.substring(0,2) == "0x" &&
          accum.headOption.isDefined && List(OP_PUSHDATA1, OP_PUSHDATA2,OP_PUSHDATA4).contains(accum.head)) =>
          logger.debug("Found a hexadecimal number preceded by an OP_PUSHDATA operation")
          //this is weird because the number is unsigned unlike other numbers
          //in bitcoin, but it is still encoded in little endian hence the .reverse call
          val byteToPushOntoStack = BytesToPushOntoStackImpl(
            java.lang.Long.parseLong(BitcoinSUtil.flipEndianess(h.slice(2,h.size).toLowerCase),16).toInt)
          loop(t, byteToPushOntoStack :: accum)

        //if we see a byte constant of just 0x09
        //parse the characters as a hex op
        case h :: t if (h.size == 4 && h.substring(0,2) == "0x") =>
          logger.debug("Found a script operation")
          val hexString = h.substring(2,h.size)
          logger.debug("Hex string: " + hexString)
          loop(t,ScriptOperationFactory.fromHex(hexString).get :: accum)

        //if we see a byte constant in the form of "0x09adb"
        case h  :: t if (h.size > 1 && h.substring(0,2) == "0x") =>
          logger.debug("Found a hexadecimal number")
          loop(t,parseBytesFromString(h) ++ accum)
        //skip the empty string
        case h :: t if (h == "") => loop(t,accum)
        case h :: t if (h == "0") => loop(t, OP_0 :: accum)


        case h :: t if (ScriptOperationFactory.fromString(h).isDefined) =>
          logger.debug("Founding a script operation in string form i.e. NOP or ADD")
          val op = ScriptOperationFactory.fromString(h).get
          val parsingHelper : ParsingHelper[String] = parseOperationString(op,accum,t)
          loop(parsingHelper.tail,parsingHelper.accum)
        case h :: t if (tryParsingLong(h)) =>
          logger.debug("Found a decimal number")
          //convert the string to int, then convert to hex
          loop(t, ScriptNumberImpl(h.toLong) :: accum)
        //means that it must be a BytesToPushOntoStack followed by a script constant
        case h :: t =>
          //find the size of the string in bytes
          val bytesToPushOntoStack = BytesToPushOntoStackImpl(h.size / 2)
          loop(t, ScriptConstantImpl(h) :: bytesToPushOntoStack :: accum)
        case Nil => accum
      }
    }
    if (tryParsingLong(str) && str.size > 1 && str.substring(0,2) != "0x") {
      //for the case when there is just a single decimal constant
      //i.e. "8388607"
      List(ScriptNumberImpl(parseLong(str)))
    }
    else if (BitcoinSUtil.isHex(str)) {
      //if the given string is hex, it is pretty straight forward to parse it
      //convert the hex string to a byte array and parse it
      val bytes = BitcoinSUtil.decodeHex(str)
      parse(bytes)
    } else {
      //this handles weird cases for parsing with various formats in bitcoin core.
      //take a look at https://github.com/bitcoin/bitcoin/blob/605c17844ea32b6d237db6d83871164dc7d59dab/src/core_read.cpp#L53-L88
      //for the offical parsing algorithm, for examples of weird formats look inside of
      //https://github.com/bitcoin/bitcoin/blob/master/src/test/data/script_valid.json
      loop(str.split(" ").toList, List()).reverse
    }
  }





  /**
   * Parses a byte array into a the asm operations for a script
   * will throw an exception if it fails to parse a op code
   * @param bytes
   * @return
   */
  private def parse(bytes : List[Byte]) : List[ScriptToken] = {
    logger.debug("Parsing byte list: " + bytes + " into a list of script tokens")
    @tailrec
    def loop(bytes : List[Byte], accum : List[ScriptToken]) : List[ScriptToken] = {
      logger.debug("Byte to be parsed: " + bytes.headOption)
      bytes match {
        case h :: t =>
          val op  = ScriptOperationFactory.fromByte(h).get
          val parsingHelper : ParsingHelper[Byte] = parseOperationByte(op,accum,t)
          loop(parsingHelper.tail,parsingHelper.accum)
        case Nil => accum
      }
    }
    loop(bytes, List()).reverse

  }

  private def parse(bytes : Seq[Byte]) : List[ScriptToken] = parse(bytes.toList)




  /**
   * Parses a redeem script from the given script token
   * @param scriptToken
   * @return
   */
  def parseRedeemScript(scriptToken : ScriptToken) : Try[List[ScriptToken]] = {
    val redeemScript : Try[List[ScriptToken]] = Try(parse(scriptToken.bytes))
    redeemScript
  }


  /**
   * Detects if the given script token is a redeem script
   * @param token
   * @return
   */
  private def isRedeemScript(token : ScriptToken) : Boolean = {
    logger.debug("Checking if last token is redeem script")
    val tryRedeemScript = parseRedeemScript(token)
    tryRedeemScript match {
      case Success(redeemScript) =>
        if (redeemScript.size > 0 ) redeemScript.last == OP_CHECKMULTISIG || redeemScript.last == OP_CHECKMULTISIGVERIFY
        else false
      case Failure(_) => false
    }
  }

  /**
   * Slices the amount of bytes specified in the bytesToPushOntoStack parameter and then creates a script constant
   * from those bytes. Returns the script constant and the byte array without the script constant
   * @param bytesToPushOntoStack
   * @param data
   * @tparam T
   * @return
   */
  private def sliceConstant[T](bytesToPushOntoStack: BytesToPushOntoStack, data : List[T]) : (List[T], List[T]) = {
    val finalIndex = bytesToPushOntoStack.opCode
    val dataConstant = data.slice(0,finalIndex)
    (dataConstant,data.slice(finalIndex,data.size))
  }


  /**
   * Parses the bytes in string format, an example input would look like this
   * "0x09 0x00000000 0x00000000 0x10"
   * see https://github.com/bitcoin/bitcoin/blob/master/src/test/data/script_valid.json#L21-L25
   * for examples of this
   * @param s
   * @return
   */
  def parseBytesFromString(s: String) : List[ScriptConstant] = {
    logger.debug("Parsing bytes from string " + s)
    val scriptConstants : List[ScriptConstant] = (raw"\b0x([0-9a-f]+)\b".r
      .findAllMatchIn(s.toLowerCase)
      .map(g =>
      // 1 hex = 4 bits therefore 16 hex characters * 4 bits = 64
      // if it is not smaller than 16 hex characters it cannot
      //fit inside of a scala long
      //therefore store it as a script constant
      if (g.group(1).size <= 16) {
        ScriptNumberImpl(BitcoinSUtil.hexToLong(g.group(1)))
      } else {
        ScriptConstantImpl(g.group(1))
    }).toList)
    scriptConstants
  }


  sealed case class ParsingHelper[T](tail : List[T], accum : List[ScriptToken])

  /**
   * Parses an operation if the tail is a List[Byte]
   * If the operation is a bytesToPushOntoStack, it pushes the number of bytes onto the stack
   * specified by the bytesToPushOntoStack
   * i.e. If the operation was BytesToPushOntoStackImpl(5), it would slice 5 bytes off of the tail and
   * places them into a ScriptConstant and add them to the accumulator.
   * @param op
   * @param accum
   * @param tail
   * @return
   */
  private def parseOperationByte(op : ScriptOperation, accum : List[ScriptToken], tail : List[Byte]) : ParsingHelper[Byte] = {
    op match {
      case bytesToPushOntoStack : BytesToPushOntoStack =>
        logger.debug("Parsing operation byte: " +bytesToPushOntoStack )
        //means that we need to push x amount of bytes on to the stack
        val (constant,newTail) = sliceConstant(bytesToPushOntoStack,tail)
        val scriptConstant = new ScriptConstantImpl(constant)
        ParsingHelper(newTail,scriptConstant :: bytesToPushOntoStack ::  accum)
      case OP_PUSHDATA1 => parseOpPushData(op,accum,tail)
      case OP_PUSHDATA2 => parseOpPushData(op,accum,tail)
      case OP_PUSHDATA4 => parseOpPushData(op,accum,tail)
      case _ =>
        //means that we need to push the operation onto the stack
        ParsingHelper(tail,op :: accum)
    }
  }


  /**
   * Parses OP_PUSHDATA operations correctly. Slices the appropriate amount of bytes off of the tail and pushes
   * them onto the accumulator.
   * @param op
   * @param accum
   * @param tail
   * @return
   */
  private def parseOpPushData(op : ScriptOperation, accum : List[ScriptToken], tail : List[Byte]) : ParsingHelper[Byte] = {
    op match {
      case OP_PUSHDATA1 =>
        //next byte is size of the script constant
        val bytesToPushOntoStack = BytesToPushOntoStackImpl(Integer.parseInt(BitcoinSUtil.encodeHex(tail.head), 16))
        val scriptConstant = new ScriptConstantImpl(tail.slice(1,bytesToPushOntoStack.num+1))
        ParsingHelper[Byte](tail.slice(bytesToPushOntoStack.num+1,tail.size),
          scriptConstant :: bytesToPushOntoStack :: op :: accum)
      case OP_PUSHDATA2 =>
        //next 2 bytes is the size of the script constant
        val scriptConstantHex = BitcoinSUtil.encodeHex(tail.slice(0,2))
        val bytesToPushOntoStack = BytesToPushOntoStackImpl(Integer.parseInt(scriptConstantHex, 16))
        val scriptConstant = new ScriptConstantImpl(tail.slice(2,bytesToPushOntoStack.num + 2))
        ParsingHelper[Byte](tail.slice(bytesToPushOntoStack.num + 2,tail.size),
          scriptConstant :: bytesToPushOntoStack :: op ::  accum)
      case OP_PUSHDATA4 =>
        //nextt 4 bytes is the size of the script constant
        val scriptConstantHex = BitcoinSUtil.encodeHex(tail.slice(0,4))
        val bytesToPushOntoStack = BytesToPushOntoStackImpl(Integer.parseInt(scriptConstantHex, 16))
        val scriptConstant = new ScriptConstantImpl(tail.slice(4,bytesToPushOntoStack.num + 4))
        ParsingHelper[Byte](tail.slice(bytesToPushOntoStack.num + 4,tail.size),
          scriptConstant :: bytesToPushOntoStack :: op :: accum)
      case _ => throw new RuntimeException("parseOpPushData can only parse OP_PUSHDATA operations")
    }
  }

  /**
   * Parses an operation if the tail is a List[String]
   * If the operation is a bytesToPushOntoStack, it pushes the number of bytes onto the stack
   * specified by the bytesToPushOntoStack
   * i.e. If the operation was BytesToPushOntoStackImpl(5), it would slice 5 bytes off of the tail and
   * places them into a ScriptConstant and add them to the accumulator.
   * @param op
   * @param accum
   * @param tail
   * @return
   */
  private def parseOperationString(op : ScriptOperation, accum : List[ScriptToken], tail : List[String]) : ParsingHelper[String] = {
    op match {
      case bytesToPushOntoStack : BytesToPushOntoStack =>
        //means that we need to push x amount of bytes on to the stack
        val (constant,newTail) = sliceConstant[String](bytesToPushOntoStack,tail)
        val scriptConstant = ScriptConstantImpl(constant.mkString)
        ParsingHelper(newTail,scriptConstant :: bytesToPushOntoStack ::  accum)

      case _ =>
        //means that we need to push the operation onto the stack
        ParsingHelper(tail,op :: accum)
    }
  }


  /**
   * Checks if a string can be cast to an int
   * @param str
   * @return
   */
  private def tryParsingLong(str : String) = try {
      parseLong(str)
      true
    } catch {
    case _ : Throwable => false
  }

  private def parseLong(str : String) = {
    if (str.substring(0,2) == "0x") {
      val strRemoveHex = str.substring(2,str.size)
      BitcoinSUtil.hexToLong(strRemoveHex)
    } else str.toLong
  }
}

object ScriptParser extends ScriptParser
