package com.example.whoopdavidapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class WhoopDavidApiApplication

fun main(args: Array<String>) {
    runApplication<WhoopDavidApiApplication>(*args)
}
