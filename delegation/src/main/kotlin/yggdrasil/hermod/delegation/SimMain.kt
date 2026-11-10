package yggdrasil.hermod.delegation

/** Standalone scoring simulator for the gate/demo: `./gradlew :delegation:runSim` */
fun main() {
    val port = System.getenv("HERMOD_DELEGATION_SIM_PORT")?.toIntOrNull() ?: 9099
    val sim = EmbeddedScoringSim(port).start()
    println("[delegation-sim] scoring sim listening on :$port")  // gate waits for this line
    Runtime.getRuntime().addShutdownHook(Thread { sim.close() })
    Thread.currentThread().join()
}
