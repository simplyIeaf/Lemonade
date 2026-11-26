package com.leaf.lemonade.core

class CollisionGroupError(message: String) : Exception(message)

class CollisionGroupTable {
    private val names = arrayListOf(DEFAULT)
    private val off = HashSet<Pair<String, String>>()
    @Volatile var version = 0L

    fun names(): List<String> = names

    fun id(name: String): Int? = names.indexOf(name).takeIf { it >= 0 }

    fun isRegistered(name: String) = id(name) != null

    @Synchronized
    fun register(name: String) {
        checkName(name)
        if (names.size >= MAX_GROUPS) throw CollisionGroupError("Cannot register more than 32 collision groups.")
        if (isRegistered(name)) throw CollisionGroupError("Could not register collision group, the name already exists.")
        names.add(name)
        version++
    }

    @Synchronized
    fun unregister(name: String) {
        if (name == DEFAULT) return
        val i = id(name) ?: return
        names.removeAt(i)
        off.removeAll { it.first == name || it.second == name }
        version++
    }

    @Synchronized
    fun rename(from: String, to: String) {
        val i = id(from) ?: return
        names[i] = to
        val moved = off.map { (a, b) -> pair(if (a == from) to else a, if (b == from) to else b) }
        off.clear()
        off.addAll(moved)
        version++
    }

    @Synchronized
    fun setCollidable(a: String, b: String, on: Boolean) {
        val p = pair(a, b)
        val changed = if (on) off.remove(p) else off.add(p)
        if (changed) version++
    }

    fun setCollidableChecked(a: String, b: String, on: Boolean) {
        if (!isRegistered(a) || !isRegistered(b)) throw CollisionGroupError("Both collision groups must be registered.")
        setCollidable(a, b, on)
    }

    @Synchronized
    fun collidable(a: String, b: String) = !off.contains(pair(a, b))

    fun bits(group: String): Pair<Int, Int> {
        val name = if (isRegistered(group)) group else DEFAULT
        val me = id(name) ?: 0
        var filter = 0
        for ((i, other) in names.withIndex()) if (collidable(name, other)) filter = filter or (1 shl i)
        return (1 shl me) to filter
    }

    fun groupsCollide(a: String, b: String): Boolean {
        val x = if (isRegistered(a)) a else DEFAULT
        val y = if (isRegistered(b)) b else DEFAULT
        return collidable(x, y)
    }

    fun reset() {
        synchronized(this) {
            names.clear(); names.add(DEFAULT)
            off.clear()
            version++
        }
    }

    private fun pair(a: String, b: String) = if (a <= b) a to b else b to a

    companion object {
        const val MAX_GROUPS = 32
        const val DEFAULT = "Default"
        const val MAX_NAME_LEN = 100

        fun checkName(name: String) {
            if (name.isEmpty()) throw CollisionGroupError("Collision group requires a valid name.")
            if (name == DEFAULT) throw CollisionGroupError("Can not use/change the Default collision group.")
            if (name.length > MAX_NAME_LEN) throw CollisionGroupError("Collision group name length cannot exceed $MAX_NAME_LEN chars")
        }

        val shared = CollisionGroupTable()
    }
}
