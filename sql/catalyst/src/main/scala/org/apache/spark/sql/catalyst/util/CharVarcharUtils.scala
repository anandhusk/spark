/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.util

import scala.collection.mutable

import org.apache.spark.internal.Logging
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

object CharVarcharUtils extends Logging {

  private val CHAR_VARCHAR_TYPE_STRING_METADATA_KEY = "__CHAR_VARCHAR_TYPE_STRING"

  /**
   * Replaces CharType/VarcharType with StringType recursively in the given struct type. If a
   * top-level StructField's data type is CharType/VarcharType or has nested CharType/VarcharType,
   * this method will add the original type string to the StructField's metadata, so that we can
   * re-construct the original data type with CharType/VarcharType later when needed.
   */
  def replaceCharVarcharWithStringInSchema(st: StructType): StructType = {
    StructType(st.map { field =>
      if (hasCharVarchar(field.dataType)) {
        val metadata = new MetadataBuilder().withMetadata(field.metadata)
          .putString(CHAR_VARCHAR_TYPE_STRING_METADATA_KEY, field.dataType.sql).build()
        field.copy(dataType = replaceCharVarcharWithString(field.dataType), metadata = metadata)
      } else {
        field
      }
    })
  }

  /**
   * Returns true if the given data type is CharType/VarcharType or has nested CharType/VarcharType.
   */
  def hasCharVarchar(dt: DataType): Boolean = {
    dt.existsRecursively(f => f.isInstanceOf[CharType] || f.isInstanceOf[VarcharType])
  }

  /**
   * Validate the given [[DataType]] to fail if it is char or varchar types or contains nested ones
   */
  def failIfHasCharVarchar(dt: DataType): DataType = {
    if (!SQLConf.get.charVarcharAsString && hasCharVarchar(dt)) {
      throw new AnalysisException("char/varchar type can only be used in the table schema. " +
        s"You can set ${SQLConf.LEGACY_CHAR_VARCHAR_AS_STRING.key} to true, so that Spark" +
        s" treat them as string type as same as Spark 3.0 and earlier")
    } else {
      replaceCharVarcharWithString(dt)
    }
  }

  /**
   * Replaces CharType/VarcharType with StringType recursively in the given data type.
   */
  def replaceCharVarcharWithString(dt: DataType): DataType = dt match {
    case ArrayType(et, nullable) =>
      ArrayType(replaceCharVarcharWithString(et), nullable)
    case MapType(kt, vt, nullable) =>
      MapType(replaceCharVarcharWithString(kt), replaceCharVarcharWithString(vt), nullable)
    case StructType(fields) =>
      StructType(fields.map { field =>
        field.copy(dataType = replaceCharVarcharWithString(field.dataType))
      })
    case _: CharType => StringType
    case _: VarcharType => StringType
    case _ => dt
  }

  /**
   * Replaces CharType/VarcharType with StringType recursively in the given data type, with a
   * warning message if it has char or varchar types
   */
  def replaceCharVarcharWithStringForCast(dt: DataType): DataType = {
    if (SQLConf.get.charVarcharAsString) {
      replaceCharVarcharWithString(dt)
    } else if (hasCharVarchar(dt)) {
      logWarning("The Spark cast operator does not support char/varchar type and simply treats" +
        " them as string type. Please use string type directly to avoid confusion. Otherwise," +
        s" you can set ${SQLConf.LEGACY_CHAR_VARCHAR_AS_STRING.key} to true, so that Spark treat" +
        s" them as string type as same as Spark 3.0 and earlier")
      replaceCharVarcharWithString(dt)
    } else {
      dt
    }
  }

  /**
   * Removes the metadata entry that contains the original type string of CharType/VarcharType from
   * the given attribute's metadata.
   */
  def cleanAttrMetadata(attr: AttributeReference): AttributeReference = {
    val cleaned = new MetadataBuilder().withMetadata(attr.metadata)
      .remove(CHAR_VARCHAR_TYPE_STRING_METADATA_KEY).build()
    attr.withMetadata(cleaned)
  }

  /**
   * Re-construct the original data type from the type string in the given metadata.
   * This is needed when dealing with char/varchar columns/fields.
   */
  def getRawType(metadata: Metadata): Option[DataType] = {
    if (metadata.contains(CHAR_VARCHAR_TYPE_STRING_METADATA_KEY)) {
      Some(CatalystSqlParser.parseDataType(
        metadata.getString(CHAR_VARCHAR_TYPE_STRING_METADATA_KEY)))
    } else {
      None
    }
  }

  /**
   * Re-construct the original schema from the type string in the given metadata of each field.
   */
  def getRawSchema(schema: StructType): StructType = {
    val fields = schema.map { field =>
      getRawType(field.metadata).map(dt => field.copy(dataType = dt)).getOrElse(field)
    }
    StructType(fields)
  }

  /**
   * Returns expressions to apply read-side char type padding for the given attributes.
   *
   * For a CHAR(N) column/field and the length of string value is M
   * If M > N, raise runtime error
   * If M <= N, the value should be right-padded to N characters.
   *
   * For a VARCHAR(N) column/field and the length of string value is M
   * If M > N, raise runtime error
   * If M <= N, the value should be remained.
   */
  def paddingWithLengthCheck(output: Seq[AttributeReference]): Seq[NamedExpression] = {
    output.map { attr =>
      getRawType(attr.metadata).filter { rawType =>
        rawType.existsRecursively(dt => dt.isInstanceOf[CharType] || dt.isInstanceOf[VarcharType])
      }.map { rawType =>
        Alias(paddingWithLengthCheck(attr, rawType), attr.name)(
          explicitMetadata = Some(attr.metadata))
      }.getOrElse(attr)
    }
  }

  private def paddingWithLengthCheck(expr: Expression, dt: DataType): Expression = dt match {
    case CharType(length) =>
      StringRPad(stringLengthCheck(expr, dt, needTrim = false), Literal(length))

    case VarcharType(_) => stringLengthCheck(expr, dt, needTrim = false)

    case StructType(fields) =>
      val struct = CreateNamedStruct(fields.zipWithIndex.flatMap { case (f, i) =>
        Seq(Literal(f.name),
          paddingWithLengthCheck(GetStructField(expr, i, Some(f.name)), f.dataType))
      })
      if (expr.nullable) {
        If(IsNull(expr), Literal(null, struct.dataType), struct)
      } else {
        struct
      }

    case ArrayType(et, containsNull) => charTypePaddingInArray(expr, et, containsNull)

    case MapType(kt, vt, valueContainsNull) =>
      val newKeys = charTypePaddingInArray(MapKeys(expr), kt, containsNull = false)
      val newValues = charTypePaddingInArray(MapValues(expr), vt, valueContainsNull)
      MapFromArrays(newKeys, newValues)

    case _ => expr
  }

  private def charTypePaddingInArray(
      arr: Expression, et: DataType, containsNull: Boolean): Expression = {
    val param = NamedLambdaVariable("x", replaceCharVarcharWithString(et), containsNull)
    val func = LambdaFunction(paddingWithLengthCheck(param, et), Seq(param))
    ArrayTransform(arr, func)
  }

  /**
   * Returns an expression to apply write-side string length check for the given expression. A
   * string value can not exceed N characters if it's written into a CHAR(N)/VARCHAR(N)
   * column/field.
   */
  def stringLengthCheck(expr: Expression, targetAttr: Attribute): Expression = {
    getRawType(targetAttr.metadata).map { rawType =>
      stringLengthCheck(expr, rawType, needTrim = true)
    }.getOrElse(expr)
  }

  private def raiseError(typeName: String, length: Int): Expression = {
    val errMsg = UTF8String.fromString(s"Exceeds $typeName type length limitation: $length")
    RaiseError(Literal(errMsg, StringType), StringType)
  }

  private def stringLengthCheck(expr: Expression, dt: DataType, needTrim: Boolean): Expression = {
    dt match {
      case CharType(length) =>
        val trimmed = if (needTrim) StringTrimRight(expr) else expr
        // Trailing spaces do not count in the length check. We don't need to retain the trailing
        // spaces, as we will pad char type columns/fields at read time.
        If(
          GreaterThan(Length(trimmed), Literal(length)),
          raiseError("char", length),
          trimmed)

      case VarcharType(length) =>
        if (needTrim) {
          val trimmed = StringTrimRight(expr)
          // Trailing spaces do not count in the length check. We need to retain the trailing spaces
          // (truncate to length N), as there is no read-time padding for varchar type.
          // TODO: create a special TrimRight function that can trim to a certain length.
          If(
            LessThanOrEqual(Length(expr), Literal(length)),
            expr,
            If(
              GreaterThan(Length(trimmed), Literal(length)),
              raiseError("varchar", length),
              StringRPad(trimmed, Literal(length))))
        } else {
          If(GreaterThan(Length(expr), Literal(length)), raiseError("varchar", length), expr)
        }

      case StructType(fields) =>
        val struct = CreateNamedStruct(fields.zipWithIndex.flatMap { case (f, i) =>
          Seq(Literal(f.name),
            stringLengthCheck(GetStructField(expr, i, Some(f.name)), f.dataType, needTrim))
        })
        if (expr.nullable) {
          If(IsNull(expr), Literal(null, struct.dataType), struct)
        } else {
          struct
        }

      case ArrayType(et, containsNull) => stringLengthCheckInArray(expr, et, containsNull, needTrim)

      case MapType(kt, vt, valueContainsNull) =>
        val newKeys = stringLengthCheckInArray(MapKeys(expr), kt, containsNull = false, needTrim)
        val newValues = stringLengthCheckInArray(MapValues(expr), vt, valueContainsNull, needTrim)
        MapFromArrays(newKeys, newValues)

      case _ => expr
    }
  }

  private def stringLengthCheckInArray(
      arr: Expression, et: DataType, containsNull: Boolean, needTrim: Boolean): Expression = {
    val param = NamedLambdaVariable("x", replaceCharVarcharWithString(et), containsNull)
    val func = LambdaFunction(stringLengthCheck(param, et, needTrim), Seq(param))
    ArrayTransform(arr, func)
  }

  /**
   * Return expressions to apply char type padding for the string comparison between the given
   * attributes. When comparing two char type columns/fields, we need to pad the shorter one to
   * the longer length.
   */
  def addPaddingInStringComparison(attrs: Seq[Attribute]): Seq[Expression] = {
    val rawTypes = attrs.map(attr => getRawType(attr.metadata))
    if (rawTypes.exists(_.isEmpty)) {
      attrs
    } else {
      val typeWithTargetCharLength = rawTypes.map(_.get).reduce(typeWithWiderCharLength)
      attrs.zip(rawTypes.map(_.get)).map { case (attr, rawType) =>
        padCharToTargetLength(attr, rawType, typeWithTargetCharLength).getOrElse(attr)
      }
    }
  }

  private def typeWithWiderCharLength(type1: DataType, type2: DataType): DataType = {
    (type1, type2) match {
      case (CharType(len1), CharType(len2)) =>
        CharType(math.max(len1, len2))
      case (StructType(fields1), StructType(fields2)) =>
        assert(fields1.length == fields2.length)
        StructType(fields1.zip(fields2).map { case (left, right) =>
          StructField("", typeWithWiderCharLength(left.dataType, right.dataType))
        })
      case (ArrayType(et1, _), ArrayType(et2, _)) =>
        ArrayType(typeWithWiderCharLength(et1, et2))
      case _ => NullType
    }
  }

  private def padCharToTargetLength(
      expr: Expression,
      rawType: DataType,
      typeWithTargetCharLength: DataType): Option[Expression] = {
    (rawType, typeWithTargetCharLength) match {
      case (CharType(len), CharType(target)) if target > len =>
        Some(StringRPad(expr, Literal(target)))

      case (StructType(fields), StructType(targets)) =>
        assert(fields.length == targets.length)
        var i = 0
        var needPadding = false
        val createStructExprs = mutable.ArrayBuffer.empty[Expression]
        while (i < fields.length) {
          val field = fields(i)
          val fieldExpr = GetStructField(expr, i, Some(field.name))
          val padded = padCharToTargetLength(fieldExpr, field.dataType, targets(i).dataType)
          needPadding = padded.isDefined
          createStructExprs += Literal(field.name)
          createStructExprs += padded.getOrElse(fieldExpr)
          i += 1
        }
        if (needPadding) Some(CreateNamedStruct(createStructExprs.toSeq)) else None

      case (ArrayType(et, containsNull), ArrayType(target, _)) =>
        val param = NamedLambdaVariable("x", replaceCharVarcharWithString(et), containsNull)
        padCharToTargetLength(param, et, target).map { padded =>
          val func = LambdaFunction(padded, Seq(param))
          ArrayTransform(expr, func)
        }

      // We don't handle MapType here as it's not comparable.

      case _ => None
    }
  }
}
