package com.example.whoopdavidapi.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun customOpenAPI(): OpenAPI {
        val securitySchemeName = "basicAuth"
        return OpenAPI()
            .info(
                Info()
                    .title("Whoop David API")
                    .description(
                        """
                        API REST intermediaria entre Whoop API v2 y Power BI.

                        **Patron BFF (Backend For Frontend)** para un solo usuario.

                        **Datos disponibles:**
                        - Cycles (ciclos fisiologicos)
                        - Recovery (puntuacion de recuperacion)
                        - Sleep (datos de sueno)
                        - Workouts (entrenamientos)
                        - Profile (perfil del usuario)

                        **Autenticacion:** HTTP Basic Auth (usuario/password de Power BI)
                        """.trimIndent()
                    )
                    .version("1.0.0")
                    .contact(
                        Contact()
                            .name("AppToLast")
                            .url("https://apptolast.com")
                    )
                    .license(
                        License()
                            .name("Apache 2.0")
                            .url("https://www.apache.org/licenses/LICENSE-2.0")
                    )
            )
            .addSecurityItem(SecurityRequirement().addList(securitySchemeName))
            .components(
                Components()
                    .addSecuritySchemes(
                        securitySchemeName,
                        SecurityScheme()
                            .name(securitySchemeName)
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("basic")
                    )
            )
            .servers(
                listOf(
                    Server()
                        .url("https://david-whoop-dev.apptolast.com")
                        .description("Servidor de desarrollo (DEV)"),
                    Server()
                        .url("https://david-whoop.apptolast.com")
                        .description("Servidor de produccion (PROD)"),
                    Server()
                        .url("http://localhost:8080")
                        .description("Servidor local (desarrollo)")
                )
            )
    }
}
