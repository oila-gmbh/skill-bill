package skillbill.ports.process

fun interface DaemonThreadPort {
  fun runWithJoinBudget(action: () -> Unit, joinBudgetMillis: Long)
}
