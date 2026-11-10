package yggdrasil.hermod.opcua

/** Standalone simulator process for the gate/demo: `./gradlew :opcua:runSim` */
fun main() {
    val sim = EmbeddedMiloSim().start()
    println("[opcua-sim] OPC-UA sim listening on ${SiteModel.default().endpoint}")  // gate waits for this line
    Runtime.getRuntime().addShutdownHook(Thread { sim.close() })
    Thread.currentThread().join()
}
