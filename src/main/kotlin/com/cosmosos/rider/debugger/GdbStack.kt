package com.cosmosos.rider.debugger

import com.cosmosos.rider.debugger.mi.MiParser
import com.cosmosos.rider.debugger.mi.MiTuple
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation
import java.io.File
import javax.swing.Icon

// A gdb variable object: https://sourceware.org/gdb/current/onlinedocs/gdb.html/GDB_002fMI-Variable-Objects.html
data class VarObj(
    val name: String,
    val value: String?,
    val type: String?,
    val numChild: Int,
    val hasMore: Boolean
) {
    val hasChildren: Boolean
        get() = numChild > 0 || hasMore

    companion object {
        fun from(t: MiTuple) = VarObj(
            name = t.string("name").orEmpty(),
            value = t.string("value"),
            type = t.string("type"),
            numChild = t.int("numchild") ?: 0,
            // Pretty-printed (dynamic) varobjs report numchild=0 and signal
            // children through has_more instead.
            hasMore = t.string("has_more") == "1"
        )
    }
}

class GdbSuspendContext(
    private val process: CosmosDebugProcess,
    val threadId: Int,
    topFrame: GdbStackFrame?
) : XSuspendContext() {
    private val activeStack = GdbExecutionStack(process, threadId, "Thread $threadId", topFrame)

    override fun getActiveExecutionStack(): XExecutionStack = activeStack

    // gdb sees one thread per vCPU.
    override fun computeExecutionStacks(container: XExecutionStackContainer) {
        process.mi("-thread-info").whenComplete { record, error ->
            if (error != null || record == null) {
                container.addExecutionStack(listOf(activeStack), true)
                return@whenComplete
            }
            val stacks = record.results.list("threads")?.tuples().orEmpty().mapNotNull { t ->
                val id = t.int("id") ?: return@mapNotNull null
                if (id == threadId) return@mapNotNull activeStack
                val name = t.string("name") ?: t.string("target-id") ?: "Thread $id"
                GdbExecutionStack(process, id, name, t.tuple("frame")?.let { GdbStackFrame.from(process, id, it) })
            }
            container.addExecutionStack(stacks.ifEmpty { listOf(activeStack) }, true)
        }
    }
}

class GdbExecutionStack(
    private val process: CosmosDebugProcess,
    private val threadId: Int,
    displayName: String,
    private val topFrame: GdbStackFrame?
) : XExecutionStack(displayName, AllIcons.Debugger.ThreadSuspended) {

    override fun getTopFrame(): XStackFrame? = topFrame

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        process.mi("-stack-list-frames --thread $threadId 0 $MAX_FRAMES").whenComplete { record, error ->
            if (error != null || record == null) {
                container.errorOccurred(error?.message ?: "No stack")
                return@whenComplete
            }
            val frames = record.results.list("stack")?.tuples().orEmpty().map { GdbStackFrame.from(process, threadId, it) }
            container.addStackFrames(frames.drop(firstFrameIndex), true)
        }
    }

    companion object {
        private const val MAX_FRAMES = 255
    }
}

class GdbStackFrame(
    private val process: CosmosDebugProcess,
    val threadId: Int,
    val level: Int,
    private val function: String?,
    private val file: String?,
    private val line: Int,
    private val address: String?
) : XStackFrame() {

    private val position: XSourcePosition? by lazy {
        val path = file ?: return@lazy null
        if (line <= 0) return@lazy null
        val ioFile = File(path)
        val vf = LocalFileSystem.getInstance().findFileByIoFile(ioFile)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
            ?: return@lazy null
        XDebuggerUtil.getInstance().createPosition(vf, line - 1)
    }

    override fun getSourcePosition(): XSourcePosition? = position

    override fun getEvaluator(): XDebuggerEvaluator = GdbEvaluator(process, threadId, level)

    override fun getEqualityObject(): Any = "${function}@${file}"

    override fun customizePresentation(component: ColoredTextContainer) {
        component.append(function ?: address ?: "??", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        if (file != null && line > 0) {
            component.append("  ${File(file).name}:$line", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        } else if (address != null && function != null) {
            component.append("  $address", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        component.setIcon(AllIcons.Debugger.Frame)
    }

    override fun computeChildren(node: XCompositeNode) {
        process.mi("-stack-list-variables --thread $threadId --frame $level --simple-values").whenComplete { record, error ->
            if (error != null || record == null) {
                node.setErrorMessage(error?.message ?: "No variables")
                return@whenComplete
            }
            val children = XValueChildrenList()
            for (v in record.results.list("variables")?.tuples().orEmpty()) {
                val name = v.string("name") ?: continue
                val icon = if (v.string("arg") == "1") AllIcons.Nodes.Parameter else AllIcons.Nodes.Variable
                children.add(GdbValue(process, name, name, threadId, level, null, icon))
            }
            node.addChildren(children, true)
        }
    }

    companion object {
        fun from(process: CosmosDebugProcess, threadId: Int, frame: MiTuple) = GdbStackFrame(
            process = process,
            threadId = threadId,
            level = frame.int("level") ?: 0,
            function = frame.string("func"),
            file = frame.string("fullname") ?: frame.string("file"),
            line = frame.int("line") ?: 0,
            address = frame.string("addr")
        )
    }
}

/**
 * A variable or expression in the Variables/Watches tree, backed by a gdb
 * varobj. Roots create theirs lazily when first shown; children come from
 * `-var-list-children` with their varobj already made.
 */
class GdbValue(
    private val process: CosmosDebugProcess,
    name: String,
    private val expression: String?,
    private val threadId: Int,
    private val frameLevel: Int,
    @Volatile private var varObj: VarObj?,
    private val icon: Icon
) : XNamedValue(name) {

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        varObj?.let {
            present(node, it)
            return
        }
        val expr = expression ?: return node.setPresentation(icon, XRegularValuePresentation("", null), false)
        process.createVarObj(expr, threadId, frameLevel).whenComplete { v, error ->
            if (error != null || v == null) {
                node.setPresentation(icon, XRegularValuePresentation("<${error?.message ?: "unavailable"}>", null), false)
            } else {
                varObj = v
                present(node, v)
            }
        }
    }

    private fun present(node: XValueNode, v: VarObj) {
        node.setPresentation(icon, XRegularValuePresentation(v.value.orEmpty(), v.type), v.hasChildren)
    }

    override fun computeChildren(node: XCompositeNode) {
        val v = varObj ?: return node.addChildren(XValueChildrenList.EMPTY, true)
        process.listChildren(v.name).whenComplete { children, error ->
            if (error != null || children == null) {
                node.setErrorMessage(error?.message ?: "No children")
                return@whenComplete
            }
            val list = XValueChildrenList()
            for ((exp, child) in children) {
                list.add(GdbValue(process, exp, null, threadId, frameLevel, child, AllIcons.Nodes.Field))
            }
            node.addChildren(list, true)
        }
    }

    override fun getEvaluationExpression(): String? = expression

    override fun getModifier(): XValueModifier? {
        val v = varObj ?: return null
        if (v.hasChildren) return null
        return object : XValueModifier() {
            override fun setValue(expression: XExpression, callback: XModificationCallback) {
                process.mi("-var-assign ${MiParser.quote(v.name)} ${MiParser.quote(expression.expression)}").whenComplete { record, error ->
                    if (error != null || record == null) {
                        callback.errorOccurred(error?.message ?: "Assignment failed")
                    } else {
                        varObj = v.copy(value = record.results.string("value") ?: v.value)
                        callback.valueModified()
                    }
                }
            }

            override fun getInitialValueEditorText(): String? = v.value
        }
    }
}

class GdbEvaluator(
    private val process: CosmosDebugProcess,
    private val threadId: Int,
    private val frameLevel: Int
) : XDebuggerEvaluator() {

    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        process.createVarObj(expression, threadId, frameLevel).whenComplete { v, error ->
            if (error != null || v == null) {
                callback.errorOccurred(error?.message ?: "Cannot evaluate")
            } else {
                callback.evaluated(GdbValue(process, expression, expression, threadId, frameLevel, v, AllIcons.Debugger.Watch))
            }
        }
    }

    // Hover evaluation: the dotted identifier chain under the caret.
    override fun getExpressionRangeAtOffset(
        project: Project,
        document: Document,
        offset: Int,
        sideEffectsAllowed: Boolean
    ): TextRange? {
        val text = document.charsSequence
        if (offset < 0 || offset >= text.length || !isIdentifierPart(text[offset])) return null
        var start = offset
        while (start > 0 && (isIdentifierPart(text[start - 1]) || text[start - 1] == '.')) start--
        var end = offset
        while (end < text.length && isIdentifierPart(text[end])) end++
        while (start < end && text[start] == '.') start++
        if (start >= end || text[start].isDigit()) return null
        return TextRange(start, end)
    }

    private fun isIdentifierPart(c: Char) = c.isLetterOrDigit() || c == '_'
}

// Watches and the Evaluate dialog edit gdb expressions as plain text.
object GdbEditorsProvider : XDebuggerEditorsProvider() {
    override fun getFileType(): FileType = PlainTextFileType.INSTANCE

    override fun createDocument(
        project: Project,
        expression: XExpression,
        sourcePosition: XSourcePosition?,
        mode: EvaluationMode
    ): Document = EditorFactory.getInstance().createDocument(expression.expression)
}
