
trait Monad[M[_]]:

  // Оборачивает чистое значение «a» в контекст монады M.
  def pure[A](a: A): M[A]


  def flatMap[A, B](ma: M[A])(f: A => M[B]): M[B]

  def map[A, B](ma: M[A])(f: A => B): M[B] =
    flatMap(ma)(a => pure(f(a)))

// То что ниже позволяет нам писать ma.flatMap(f) вместо monad.flatMap(ma)(f),
// а значит, работает for-comprehension (он раскрывается именно в такие вызовы).
extension [M[_], A](ma: M[A])(using m: Monad[M])
  def flatMap[B](f: A => M[B]): M[B] = m.flatMap(ma)(f)
  def map[B](f: A => B): M[B] = m.map(ma)(f)


// -----------------------------------------------------------------------------
//  READER[Env, A]
// Reader моделирует вычисление, которому нужна конфигурация.
//
// Ключевая идея тут: мы не передаём конфиг явно в каждую функцию.
// Вместо этого все функции возвращают Reader, а реальный Env
// подаётся один раз - в самом конце, через run.


final case class Reader[Env, A](run: Env => A)

// Экземпляр Monad для Reader.
// Фиксируем Env как константу, M[A] = Reader[Env, A].
given readerMonad[Env]: Monad[[A] =>> Reader[Env, A]] with

  // pure: игнорируем окружение и просто возвращаем значение.
  def pure[A](a: A): Reader[Env, A] =
    Reader(_ => a)

  // flatMap: запускаем ma с окружением env, получаем A,
  def flatMap[A, B](ma: Reader[Env, A])(f: A => Reader[Env, B]): Reader[Env, B] =
    Reader(env => f(ma.run(env)).run(env))

object Reader:
  // Вспомогательная функция: «дай мне само окружение».
  // Используется когда нужно явно прочитать Env внутри Reader вычисления.
  def ask[Env]: Reader[Env, Env] = Reader(env => env)

  // asks: прочитать часть окружения через функцию-экстрактор.
  def asks[Env, A](f: Env => A): Reader[Env, A] = Reader(f)


// -----------------------------------------------------------------------------
// WRITER[Log, A]
// Writer моделирует вычисление, которое накапливает лог.
// Это просто пара: результат A + накопленный лог Log.
//
// Здесь каждый шаг вычисления добавляет записи в лог,
// а flatMap автоматически объединяет логи всех шагов.
// В итоге у нас и результат, и полная история как он получился.

final case class Writer[Log, A](run: (A, Log))

// Экземпляр Monad для Writer.
// Здесь нам нужен «пустой лог» и операция объединения.
given writerMonad[Log](using lm: LogMonoid[Log]): Monad[[A] =>> Writer[Log, A]] with
  
  def pure[A](a: A): Writer[Log, A] =
    Writer((a, lm.empty))

  // flatMap: запускаем ma - получаем (a, log1),
  def flatMap[A, B](ma: Writer[Log, A])(f: A => Writer[Log, B]): Writer[Log, B] =
    val (a, log1) = ma.run
    val (b, log2) = f(a).run
    Writer((b, lm.combine(log1, log2)))

trait LogMonoid[Log]:
  def empty: Log
  def combine(l1: Log, l2: Log): Log

// Экземпляр для Vector[String] - наш стандартный тип лога.
given vectorLogMonoid: LogMonoid[Vector[String]] with
  def empty: Vector[String] = Vector.empty
  def combine(l1: Vector[String], l2: Vector[String]): Vector[String] = l1 ++ l2

object Writer:
  // tell: записать в лог, ничего не вычисляя результат - Unit.
  def tell[Log](log: Log): Writer[Log, Unit] =
    Writer(((), log))

  // Вариант для одной строки, самый частый случай.
  def log(message: String): Writer[Vector[String], Unit] =
    tell(Vector(message))


// -------------------
// 4. STATE
// -----------------------------------------------------------------------
// State моделирует вычисление, которое читает и изменяет состояние.
// По сути это обертка над функцией S => (A, S)
// Как дай мне старое состояние - верну результат и новое состояние.
//
// Ключевая идея здесь:
// Каждый переход явно берет старое состояние и возвращает новое. Отсюда
// никаких варов, никаких глобальных переменных.


final case class State[S, A](run: S => (A, S))

// Экземпляр Monad для State.
given stateMonad[S]: Monad[[A] =>> State[S, A]] with

  def pure[A](a: A): State[S, A] =
    State(s => (a, s))

  // Запускаем ma со старым состоянием s и получаем (a, s1), потом
  // применяем f(a) и получаем новый стейт, запускаем его с s1 > (b, s2).
  // Состояние протекает через всю цепочку действий.
  def flatMap[A, B](ma: State[S, A])(f: A => State[S, B]): State[S, B] =
    State(s =>
      val (a, s1) = ma.run(s)
      f(a).run(s1)
    )

object State:

  // АйЯй предложил добавить pure в этот объект, вместо того, чтобы
  // делать summon в каждом ее вызове, как я это делал раньше...
  // Ну ладно, что сказать еще... сделаем так, как говорит АйЯй.
  def pure[S, A](a: A): State[S, A] = State(s => (a, s))

  // get: прочитать текущее состояние как значение.
  def get[S]: State[S, S] =
    State(s => (s, s))

  // put: заменить состояние целиком.
  // Используем, когда строим новое состояние с нуля.
  def put[S](newState: S): State[S, Unit] =
    State(_ => ((), newState))

  // modify: изменить состояние через функцию-трансформер.
  def modify[S](f: S => S): State[S, Unit] =
    State(s => ((), f(s)))

  // gets: прочитать часть состояния через экстрактор.
  def gets[S, A](f: S => A): State[S, A] =
    State(s => (f(s), s))




final case class IO[A](unsafeRun: () => A)

// Экземпляр Monad для IO.
given ioMonad: Monad[IO] with

  // pure: оборачиваем значение.
  def pure[A](a: A): IO[A] =
    IO(() => a)

  // flatMap: когда придёт время запускаться -
  // сначала запустим ma (получим A), затем f(A) получим IO[B],
  // затем запустим его. Всё последовательно, всё лениво до вызова unsafeRun.
  def flatMap[A, B](ma: IO[A])(f: A => IO[B]): IO[B] =
    IO(() => f(ma.unsafeRun()).unsafeRun())

object IO:
  
  def pure[A](a: A): IO[A] = IO(() => a)
  // Можно оставить строчку выше, а можно делать саммон каждой такой функции.
  // Будут появляться ошибки, если не вызывать pure именно из IO (изначально он будет брать ее из Monad, как я понял...
  
  // println как IO: описываем печать, не выполняя её.
  def putStrLn(s: String): IO[Unit] =
    IO(() => println(s))

  // print без переноса строки.
  def putStr(s: String): IO[Unit] =
    IO(() => print(s))

  // readLine как IO: описываем чтение строки.
  def readLine: IO[String] =
    IO(() => scala.io.StdIn.readLine())

  // Запустить список IO-действий последовательно, собрать результаты.
  def sequence[A](actions: List[IO[A]]): IO[List[A]] =
    actions.foldRight(summon[Monad[IO]].pure(List.empty[A])) { (action, acc) => // IO.pure(List.empty[A])
      for
        a  <- action
        as <- acc
      yield a :: as
    }

  // Выполнить список IO-действий ради эффектов, результаты не нужны.
  def traverse_(actions: List[IO[Unit]]): IO[Unit] =
    actions.foldLeft(summon[Monad[IO]].pure(())) { (acc, action) => // IO.pure(())
      for
        _ <- acc
        _ <- action
      yield ()
    }