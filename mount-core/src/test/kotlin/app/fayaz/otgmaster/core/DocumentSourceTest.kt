package app.fayaz.otgmaster.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The tree contract on a fake tree, with no filesystem under it (proposal, cross-milestone verification). */
class DocumentSourceTest {

    private class Node(
        override val name: String,
        override val isDirectory: Boolean,
        override val isRoot: Boolean = false,
        val kids: MutableList<Node> = mutableListOf(),
    ) : DocumentNode {
        override val size = 0L
        override val lastModified = 0L
    }

    private class Tree : DocumentSource {
        val top = Node("", isDirectory = true, isRoot = true)
        override val readOnlyReason: String? = "fake"
        override fun root() = top
        override fun child(dir: DocumentNode, name: String) = (dir as Node).kids.firstOrNull { it.name == name }
        override fun children(dir: DocumentNode): List<DocumentNode> = (dir as Node).kids
        override fun open(node: DocumentNode): DocumentHandle = error("no content")
        override fun createFile(dir: DocumentNode, name: String) = error("read-only")
        override fun createDirectory(dir: DocumentNode, name: String) = error("read-only")
        override fun rename(node: DocumentNode, name: String) = error("read-only")
        override fun delete(node: DocumentNode) = error("read-only")
    }

    private val tree = Tree().apply {
        val photos = Node("Photos", isDirectory = true)
        photos.kids += Node("a.jpg", isDirectory = false)
        photos.kids += Node("A.jpg", isDirectory = false)
        photos.kids += Node("Größe é.txt", isDirectory = false)
        top.kids += photos
        top.kids += Node("notes.txt", isDirectory = false)
    }

    @Test
    fun `an empty path is the root`() {
        assertEquals(tree.top, tree.resolve(emptyList()))
    }

    @Test
    fun `names match exactly at each level, case and Unicode included`() {
        assertEquals("a.jpg", tree.resolve(listOf("Photos", "a.jpg"))?.name)
        assertEquals("A.jpg", tree.resolve(listOf("Photos", "A.jpg"))?.name)
        assertEquals("Größe é.txt", tree.resolve(listOf("Photos", "Größe é.txt"))?.name)
        assertNull(tree.resolve(listOf("photos", "a.jpg")))
        assertNull(tree.resolve(listOf("Photos", "b.jpg")))
    }

    @Test
    fun `a file has no children to walk into`() {
        assertNull(tree.resolve(listOf("notes.txt", "x")))
    }
}
