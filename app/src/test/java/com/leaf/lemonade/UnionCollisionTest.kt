package com.leaf.lemonade

import com.leaf.lemonade.core.SolidModeling
import com.leaf.lemonade.engine.Geometry
import com.leaf.lemonade.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unions collide with their real shape (CollisionFidelity Default), not their bounding box. */
class UnionCollisionTest {
    private fun block(size: Vector3, at: Vector3): Instance = InstanceFactory.create("Part").apply {
        set("size", size, false); set("CFrame", CFrame.at(at), false)
    }

    /** An arch: two legs (x = -5..-3 and 3..5, y = 0..6) and a top beam (y = 6..8). */
    private fun arch(fidelity: Int): Instance {
        val op = InstanceFactory.create("UnionOperation")
        block(Vector3(2f, 6f, 4f), Vector3(-4f, 3f, 0f)).attachRaw(op)
        block(Vector3(2f, 6f, 4f), Vector3(4f, 3f, 0f)).attachRaw(op)
        block(Vector3(10f, 2f, 4f), Vector3(0f, 7f, 0f)).attachRaw(op)
        val r = SolidModeling.result(op)!!
        // Placed like Studio's Union does: at the sources' bounds center plus the result offset.
        op.set("CFrame", CFrame.at(Vector3(0f, 4f, 0f) + r.offset), false)
        op.set("size", r.initialSize, false)
        op.set("CollisionFidelity", EnumValue(fidelity), false)
        return op
    }

    private fun contact(op: Instance, c: Vector3, half: Vector3) = Geometry.contact(Geometry.aabbShape(c, half), c - half, c + half, op)

    @Test fun walkThroughArchGap() {
        // A character-sized box under the beam, between the legs: no collision.
        assertNull(contact(arch(0), Vector3(0f, 2.5f, 0f), Vector3(1f, 2.5f, 0.5f)))
        // With Box fidelity the same spot is inside the union's box.
        assertNotNull(contact(arch(2), Vector3(0f, 2.5f, 0f), Vector3(1f, 2.5f, 0.5f)))
    }

    @Test fun standOnTopAndHitLegs() {
        // Sunk 0.1 into the top of the beam: pushed straight up by 0.1.
        val top = contact(arch(0), Vector3(0f, 8.9f, 0f), Vector3(1f, 1f, 0.5f))!!
        assertEquals(0f, top.first.x, 1e-3f); assertEquals(0.1f, top.first.y, 1e-3f)
        assertEquals(8f, top.second, 1e-3f)
        // Walking into the right leg from the gap: pushed back toward -X.
        val side = contact(arch(0), Vector3(2.2f, 2f, 0f), Vector3(1f, 1.5f, 0.5f))!!
        assertTrue("push ${side.first}", side.first.x < -0.1f && kotlin.math.abs(side.first.y) < 1e-3f)
    }

    @Test fun raysGoThroughTheGap() {
        val op = arch(0)
        // Horizontally through the opening under the beam: misses the arch entirely.
        assertNull(Geometry.rayPart(Vector3(0f, 3f, 10f), Vector3(0f, 0f, -1f), op))
        // Down onto the beam: hits its top at y = 8.
        val hit = Geometry.rayPart(Vector3(0f, 20f, 0f), Vector3(0f, -1f, 0f), op)!!
        assertEquals(12f, hit.first, 1e-3f)
        assertEquals(1f, hit.second.y, 1e-3f)
    }
}
