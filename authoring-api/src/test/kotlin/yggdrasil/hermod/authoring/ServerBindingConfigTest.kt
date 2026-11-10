package yggdrasil.hermod.authoring

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerBindingConfigTest {
    @Test fun `control plane binds to loopback unless HERMOD_BIND_ADDRESS says otherwise`() {
        val props = YamlPropertiesFactoryBean().apply { setResources(ClassPathResource("application.yml")) }.getObject()!!
        assertEquals("\${HERMOD_BIND_ADDRESS:127.0.0.1}", props.getProperty("server.address"))
    }
}
