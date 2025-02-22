package endpoints4s.ujson

import endpoints4s.{ujson => _, _}

import scala.collection.compat._
import scala.collection.mutable

/** @group interpreters
  */
trait JsonSchemas extends algebra.NoDocsJsonSchemas with TuplesSchemas {

  trait JsonSchema[A] {

    def encoder: Encoder[A, ujson.Value]

    def decoder: Decoder[ujson.Value, A]

    final def codec: Codec[ujson.Value, A] =
      Codec.fromEncoderAndDecoder(encoder)(decoder)

    final def stringCodec: Codec[String, A] =
      Codec.sequentially(codecs.stringJson)(codec)

  }

  trait Record[A] extends JsonSchema[A] {

    override def encoder: Encoder[A, ujson.Obj] // Result type refined to `Obj`

  }

  trait Tagged[A] extends Record[A] {
    def discriminator: String
    def findDecoder(tag: String): Option[Decoder[ujson.Value, A]]
    def tagAndObj(a: A): (String, ujson.Obj)

    final val decoder = {
      case json @ ujson.Obj(fields) =>
        fields.get(discriminator) match {
          case Some(ujson.Str(tag)) =>
            findDecoder(tag) match {
              case Some(decoder) => decoder.decode(json)
              case None          => Invalid(s"Invalid type discriminator: '$tag'")
            }
          case _ =>
            Invalid(
              s"Missing type discriminator property '$discriminator': $json"
            )
        }
      case json => Invalid(s"Invalid JSON object: $json")
    }

    final val encoder = value => {
      val (tag, json) = tagAndObj(value)
      json(discriminator) = ujson.Str(tag)
      json
    }
  }

  type Enum[A] = JsonSchema[A]

  implicit def jsonSchemaPartialInvFunctor: PartialInvariantFunctor[JsonSchema] =
    new PartialInvariantFunctor[JsonSchema] {

      def xmapPartial[A, B](
          fa: JsonSchema[A],
          f: A => Validated[B],
          g: B => A
      ): JsonSchema[B] =
        new JsonSchema[B] {
          val decoder = Decoder.sequentially(fa.decoder)(a => f(a))
          val encoder = Encoder.sequentially((b: B) => g(b))(fa.encoder)
        }
    }

  implicit def recordPartialInvFunctor: PartialInvariantFunctor[Record] =
    new PartialInvariantFunctor[Record] {

      def xmapPartial[A, B](
          fa: Record[A],
          f: A => Validated[B],
          g: B => A
      ): Record[B] =
        new Record[B] {
          val decoder = Decoder.sequentially(fa.decoder)(a => f(a))
          val encoder = Encoder.sequentially((b: B) => g(b))(fa.encoder)
        }
    }

  implicit def taggedPartialInvFunctor: PartialInvariantFunctor[Tagged] =
    new PartialInvariantFunctor[Tagged] {

      def xmapPartial[A, B](
          fa: Tagged[A],
          f: A => Validated[B],
          g: B => A
      ): Tagged[B] =
        new Tagged[B] {
          val discriminator = fa.discriminator
          def findDecoder(tag: String): Option[Decoder[ujson.Value, B]] =
            fa.findDecoder(tag).map(Decoder.sequentially(_)(a => f(a)))
          def tagAndObj(b: B): (String, ujson.Obj) = fa.tagAndObj(g(b))
        }
    }

  def enumeration[A](values: Seq[A])(tpe: JsonSchema[A]): Enum[A] =
    new JsonSchema[A] {

      val decoder = Decoder.sequentially(tpe.decoder) { a =>
        if (values.contains(a)) {
          Valid(a)
        } else {
          Invalid(
            s"Invalid value: ${tpe.encoder.encode(a)} ; valid values are: ${values
              .map(a => tpe.encoder.encode(a))
              .mkString(", ")}"
          )
        }
      }
      val encoder = tpe.encoder
    }

  override def lazySchema[A](name: String)(schema: => JsonSchema[A]): JsonSchema[A] = {
    // The schema won’t be evaluated until its `reads` or `writes` is effectively used
    lazy val evaluatedSchema = schema
    new JsonSchema[A] {
      val decoder = json => evaluatedSchema.decoder.decode(json)
      val encoder = value => evaluatedSchema.encoder.encode(value)
    }
  }

  def lazyRecord[A](schema: => Record[A], name: String): JsonSchema[A] =
    lazySchema(name)(schema)
  def lazyTagged[A](schema: => Tagged[A], name: String): JsonSchema[A] =
    lazySchema(name)(schema)

  override def lazyRecord[A](name: String)(schema: => Record[A]): Record[A] = {
    // The schema won’t be evaluated until its `reads` or `writes` is effectively used
    lazy val evaluatedSchema = schema
    new Record[A] {
      val decoder = json => evaluatedSchema.decoder.decode(json)
      val encoder = value => evaluatedSchema.encoder.encode(value)
    }
  }

  override def lazyTagged[A](name: String)(schema: => Tagged[A]): Tagged[A] = {
    // The schema won’t be evaluated until its `reads` or `writes` is effectively used
    lazy val evaluatedSchema = schema
    new Tagged[A] {
      lazy val discriminator = evaluatedSchema.discriminator
      def findDecoder(tag: String): Option[Decoder[ujson.Value, A]] =
        evaluatedSchema.findDecoder(tag)
      def tagAndObj(a: A): (String, ujson.Obj) =
        evaluatedSchema.tagAndObj(a)
    }
  }

  lazy val emptyRecord: Record[Unit] = new Record[Unit] {

    val decoder = {
      case _: ujson.Obj => Valid(())
      case json         => Invalid(s"Invalid JSON object: $json")
    }
    val encoder = _ => ujson.Obj()
  }

  /** Override this method to customize the behaviour of encoders produced by
    * [[optFieldWithDefault]] when encoding a field value that corresponds to
    * the specified default value. By default, the default values are included.
    *
    * As an example, consider the following Scala class and instances of it.
    *
    * {{{
    * case class Book(
    *   name: String,
    *   availableAsEBook: Boolean = false
    * )
    *
    * val book1 = Book("Complete Imaginary Works", false)
    * val book2 = Book("History of Writing", true)
    * }}}
    *
    * With `encodersSkipDefaultValues = false` (which is the default), the field
    * is always encoded, regardless of whether it is also the default value.
    * This makes encoding performance predictable, but results in larger and
    * more complicated encoded payloads:
    *
    * {{{
    * { "name": "Complete Imaginary Works", "availableAsEBook": false }
    * { "name": "History of Writing", "availableAsEBook": true }
    * }}}
    *
    * With `encodersSkipDefaultValues = true`, the field is skipped if its value
    * if also the field's default value. This means encoding can be slower
    * (since  potentially expensive equality check needs to be performed), but
    * the encoded payloads are smaller and simpler:
    *
    * {{{
    * { "name": "Complete Imaginary Works" }
    * { "name": "History of Writing", "availableAsEBook": true }
    * }}}
    */
  def encodersSkipDefaultValues: Boolean = false

  def field[A](name: String, documentation: Option[String] = None)(implicit
      tpe: JsonSchema[A]
  ): Record[A] =
    new Record[A] {

      val decoder = {
        case obj @ ujson.Obj(fields) =>
          fields.get(name) match {
            case Some(json) => tpe.decoder.decode(json)
            case None =>
              Invalid(s"Missing property '$name' in JSON object: $obj")
          }
        case json => Invalid(s"Invalid JSON object: $json")
      }
      val encoder = value => ujson.Obj(name -> tpe.encoder.encode(value))
    }

  def optField[A](name: String, documentation: Option[String] = None)(implicit
      tpe: JsonSchema[A]
  ): Record[Option[A]] =
    new Record[Option[A]] {

      val decoder = {
        case ujson.Obj(fields) =>
          fields.get(name) match {
            case Some(ujson.Null) => Valid(None)
            case Some(json)       => tpe.decoder.decode(json).map(Some(_))
            case None             => Valid(None)
          }
        case json => Invalid(s"Invalid JSON object: $json")
      }

      val encoder = new Encoder[Option[A], ujson.Obj] {

        def encode(maybeValue: Option[A]) =
          maybeValue match {
            case None        => ujson.Obj()
            case Some(value) => ujson.Obj(name -> tpe.codec.encode(value))
          }
      }
    }

  override def optFieldWithDefault[A](
      name: String,
      defaultValue: A,
      docs: Option[String] = None
  )(implicit
      tpe: JsonSchema[A]
  ): Record[A] =
    new Record[A] {

      val decoder = {
        case obj @ ujson.Obj(fields) =>
          fields.get(name) match {
            case Some(ujson.Null) => Valid(defaultValue)
            case Some(json)       => tpe.decoder.decode(json)
            case None             => Valid(defaultValue)
          }
        case json => Invalid(s"Invalid JSON object: $json")
      }

      val encoder: Encoder[A, ujson.Obj] =
        if (encodersSkipDefaultValues)
          value =>
            if (value == defaultValue) ujson.Obj()
            else ujson.Obj(name -> tpe.encoder.encode(value))
        else
          value => ujson.Obj(name -> tpe.encoder.encode(value))
    }

  def taggedRecord[A](recordA: Record[A], tag: String): Tagged[A] =
    new Tagged[A] {
      val discriminator = defaultDiscriminatorName
      def findDecoder(tagName: String) =
        if (tagName == tag) Some(recordA.decoder) else None
      def tagAndObj(value: A) = (tag, recordA.encoder.encode(value))
    }

  def withDiscriminatorTagged[A](
      tagged: Tagged[A],
      discriminatorName: String
  ): Tagged[A] =
    new Tagged[A] {
      val discriminator = discriminatorName
      def findDecoder(tag: String): Option[Decoder[ujson.Value, A]] =
        tagged.findDecoder(tag)
      def tagAndObj(value: A) = tagged.tagAndObj(value)
    }

  def choiceTagged[A, B](
      taggedA: Tagged[A],
      taggedB: Tagged[B]
  ): Tagged[Either[A, B]] = {
    assert(taggedA.discriminator == taggedB.discriminator)
    new Tagged[Either[A, B]] {
      val discriminator = taggedB.discriminator
      def findDecoder(tag: String): Option[Decoder[ujson.Value, Either[A, B]]] =
        taggedA
          .findDecoder(tag)
          .map(Decoder.sequentially(_)(a => Valid(Left(a)))) orElse
          taggedB
            .findDecoder(tag)
            .map(Decoder.sequentially(_)(b => Valid(Right(b))))
      def tagAndObj(value: Either[A, B]) =
        value match {
          case Left(a)  => taggedA.tagAndObj(a)
          case Right(b) => taggedB.tagAndObj(b)
        }
    }
  }

  def zipRecords[A, B](recordA: Record[A], recordB: Record[B])(implicit
      t: Tupler[A, B]
  ): Record[t.Out] =
    new Record[t.Out] {
      val decoder = (json: ujson.Value) =>
        recordA.decoder.decode(json) zip recordB.decoder.decode(json)

      val encoder = new Encoder[t.Out, ujson.Obj] {

        def encode(from: t.Out): ujson.Obj = {
          val (a, b) = t.unapply(from)
          new ujson.Obj(
            recordA.encoder.encode(a).value ++ recordB.encoder.encode(b).value
          )
        }
      }
    }

  def orFallbackToJsonSchema[A, B](
      schemaA: JsonSchema[A],
      schemaB: JsonSchema[B]
  ): JsonSchema[Either[A, B]] =
    new JsonSchema[Either[A, B]] {

      val decoder: Decoder[ujson.Value, Either[A, B]] = json => {
        schemaA.decoder.decode(json) match {
          case Valid(value) => Valid(Left(value))
          case Invalid(_) =>
            schemaB.decoder
              .decode(json)
              .map(Right(_))
              .mapErrors(_ => s"Invalid value: $json" :: Nil)
        }
      }

      val encoder: Encoder[Either[A, B], ujson.Value] = {
        case Left(a)  => schemaA.encoder.encode(a)
        case Right(b) => schemaB.encoder.encode(b)
      }
    }

  def stringJsonSchema(format: Option[String]): JsonSchema[String] =
    new JsonSchema[String] {

      val decoder = {
        case ujson.Str(str) => Valid(str)
        case json           => Invalid(s"Invalid string value: $json")
      }
      val encoder = ujson.Str(_)
    }

  implicit lazy val intJsonSchema: JsonSchema[Int] =
    intWithConstraintsJsonSchema(NumericConstraints[Int])

  implicit lazy val longJsonSchema: JsonSchema[Long] =
    longWithConstraintsJsonSchema(NumericConstraints[Long])

  implicit lazy val bigdecimalJsonSchema: JsonSchema[BigDecimal] =
    bigdecimalWithConstraintsJsonSchema(NumericConstraints[BigDecimal])

  implicit lazy val floatJsonSchema: JsonSchema[Float] =
    floatWithConstraintsJsonSchema(NumericConstraints[Float])

  implicit lazy val doubleJsonSchema: JsonSchema[Double] =
    doubleWithConstraintsJsonSchema(NumericConstraints[Double])

  override def intWithConstraintsJsonSchema(constraints: NumericConstraints[Int]): JsonSchema[Int] =
    new JsonSchema[Int] {
      val decoder = {
        case ujson.Num(x) if x.isValidInt =>
          val int = x.toInt
          if (constraints.satisfiedBy(int)) Valid(x.toInt)
          else Invalid(s"$x does not satisfy the constraints: $constraints")
        case json => Invalid(s"Invalid integer value: $json")
      }
      val encoder = n => ujson.Num(n.toDouble)
    }

  override def longWithConstraintsJsonSchema(
      constraints: NumericConstraints[Long]
  ): JsonSchema[Long] =
    new JsonSchema[Long] {
      val decoder = {
        case json @ ujson.Num(x) =>
          val y = BigDecimal(
            x
          ) // no `isValidLong` operation on `Double`, so convert to `BigDecimal`
          if (y.isValidLong) {
            val long = y.toLong
            if (constraints.satisfiedBy(long)) Valid(long)
            else Invalid(s"$x does not satisfy the constraints: $constraints")
          } else Invalid(s"Invalid integer value: $json")
        case json => Invalid(s"Invalid number value: $json")
      }
      val encoder = n => ujson.Num(n.toDouble)
    }

  override def bigdecimalWithConstraintsJsonSchema(
      constraints: NumericConstraints[BigDecimal]
  ): JsonSchema[BigDecimal] =
    new JsonSchema[BigDecimal] {
      val decoder = {
        case ujson.Num(x) if constraints.satisfiedBy(x) => Valid(BigDecimal(x))
        case ujson.Num(x)                               => Invalid(s"$x does not satisfy the constraints: $constraints")
        case json                                       => Invalid(s"Invalid number value: $json")
      }
      val encoder = x => ujson.Num(x.doubleValue)
    }

  override def floatWithConstraintsJsonSchema(
      constraints: NumericConstraints[Float]
  ): JsonSchema[Float] =
    new JsonSchema[Float] {
      val decoder = {
        case ujson.Num(x) =>
          val float = x.toFloat
          if (constraints.satisfiedBy(float)) Valid(float)
          else Invalid(s"$x does not satisfy the constraints: $constraints")
        case json => Invalid(s"Invalid number value: $json")
      }
      val encoder = x => ujson.Num(x.toDouble)
    }

  override def doubleWithConstraintsJsonSchema(
      constraints: NumericConstraints[Double]
  ): JsonSchema[Double] =
    new JsonSchema[Double] {
      val decoder = {
        case ujson.Num(x) if constraints.satisfiedBy(x) => Valid(x)
        case ujson.Num(x)                               => Invalid(s"$x does not satisfy the constraints: $constraints")
        case json                                       => Invalid(s"Invalid number value: $json")
      }
      val encoder = ujson.Num(_)
    }

  implicit def booleanJsonSchema: JsonSchema[Boolean] =
    new JsonSchema[Boolean] {
      val decoder = {
        case ujson.Bool(b) => Valid(b)
        case json          => Invalid(s"Invalid boolean value: $json")
      }
      val encoder = ujson.Bool(_)
    }

  implicit def byteJsonSchema: JsonSchema[Byte] =
    new JsonSchema[Byte] {

      val decoder = {
        case ujson.Num(x) if x.isValidByte => Valid(x.toByte)
        case json                          => Invalid(s"Invalid byte value: $json")
      }
      val encoder = b => ujson.Num(b.toDouble)
    }

  implicit def arrayJsonSchema[C[X] <: Iterable[X], A](implicit
      jsonSchema: JsonSchema[A],
      factory: Factory[A, C[A]]
  ): JsonSchema[C[A]] =
    new JsonSchema[C[A]] {

      val decoder = {
        case ujson.Arr(items) =>
          val builder = factory.newBuilder
          builder.sizeHint(items)
          items
            .map(jsonSchema.decoder.decode)
            .foldLeft[Validated[collection.mutable.Builder[A, C[A]]]](
              Valid(builder)
            ) { case (acc, value) =>
              acc.zip(value).map { case (b, a) => b += a }
            }
            .map(_.result())
        case json => Invalid(s"Invalid JSON array: $json")
      }
      val encoder = as => ujson.Arr.from(as.map(jsonSchema.codec.encode))
    }

  implicit def mapJsonSchema[A](implicit
      jsonSchema: JsonSchema[A]
  ): JsonSchema[Map[String, A]] =
    new JsonSchema[Map[String, A]] {

      val decoder = {
        case ujson.Obj(items) =>
          val builder = Map.newBuilder[String, A]
          builder.sizeHint(items)
          items
            .map { case (name, value) =>
              jsonSchema.decoder.decode(value).map((name, _))
            }
            .foldLeft[Validated[
              collection.mutable.Builder[(String, A), Map[String, A]]
            ]](Valid(builder)) { case (acc, value) =>
              acc.zip(value).map { case (b, name, value) =>
                b += name -> value
              }
            }
            .map(_.result())
        case json => Invalid(s"Invalid JSON object: $json")
      }

      val encoder = map => {
        new ujson.Obj(mutable.LinkedHashMap(map.map { case (k, v) =>
          (k, jsonSchema.codec.encode(v))
        }.toSeq: _*))
      }
    }
}
