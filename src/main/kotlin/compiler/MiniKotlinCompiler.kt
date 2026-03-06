package org.example.compiler

import MiniKotlinBaseVisitor
import MiniKotlinParser
import org.antlr.v4.runtime.tree.ParseTree

class MiniKotlinCompiler : MiniKotlinBaseVisitor<String>() {
    private var argCounter = 0
    private var loopCounter = 0

    private var currentRestCode: String = ""
    private var targetCallToReplace: MiniKotlinParser.FunctionCallExprContext? = null
    private var replaceArgName: String = ""

    private fun nextArgName() = "arg${argCounter++}"
    private fun nextLoopName() = "_Loop_${loopCounter++}"

    fun compile(program: MiniKotlinParser.ProgramContext, className: String = "MiniProgram"): String {
        val builder = java.lang.StringBuilder()

        builder.appendLine("import java.util.function.*;")
        builder.appendLine("import java.lang.*;")
        builder.appendLine("""
            interface Continuation<T> {
                void accept(T value);
            }
        """.trimIndent())
        builder.appendLine("public class $className {")

        for (function in program.functionDeclaration()) {
            builder.appendLine(visit(function))
        }

        builder.append("}")
        return builder.toString()
    }

    private fun mapType(typeText: String): String {
        return when (typeText) {
            "Int" -> "Integer"
            "String" -> "String"
            "Boolean" -> "Boolean"
            "Double" -> "Double"
            "Unit" -> "Void"
            else -> typeText
        }
    }

    override fun visitFunctionDeclaration(context: MiniKotlinParser.FunctionDeclarationContext): String {
        val functionName = context.IDENTIFIER().text

        val parameters = context.parameterList()?.parameter() ?: emptyList()

        val javaParametersString = parameters.joinToString(", ") { param ->
            "${mapType(param.type().text)} ${param.IDENTIFIER().text}"
        }

        val isMain = functionName == "main"
        val defaultRest = if (isMain) {
            ""
        } else {
            "if (__continuation != null) {\n    __continuation.accept(null);\n}\nreturn;"
        }

        var currentLocalRest = defaultRest

        val bodyStatements = context.block().statement() ?: emptyList()

        for (index in bodyStatements.indices.reversed()) {
            this.currentRestCode = currentLocalRest
            currentLocalRest = visit(bodyStatements[index])
        }

        if (isMain) {
            return """
                public static void main(String[] args) {
                    ${currentLocalRest.prependIndent("    ")}
                }
            """.trimIndent()
        } else {
            val separator = if (javaParametersString.isEmpty()) {
                ""
            } else {
                ", "
            }

            val javaReturnType = mapType(context.type().text)

            return """
                public static void $functionName($javaParametersString$separator Continuation<$javaReturnType> __continuation) {
                ${currentLocalRest.prependIndent("    ")}
                }
            """.trimIndent()
        }
    }

    override fun visitStatement(context: MiniKotlinParser.StatementContext): String {
        if (context.expression() != null) {
            val expression = context.expression()

            val rest = this.currentRestCode

            val call = extractInnerFunctionCall(expression)

            return if (call != null) {
                generateCPSCall(call) { rest }
            } else {
                "${visit(expression)};\n$rest"
            }
        }

        return super.visitStatement(context)
    }

    override fun visitVariableDeclaration(context: MiniKotlinParser.VariableDeclarationContext): String {
        val name = context.IDENTIFIER().text
        val type = mapType(context.type().text)
        val rest = this.currentRestCode
        val expression = context.expression()

        val call = extractInnerFunctionCall(expression)

        return if (call != null) {
            generateCPSCall(call) { arg ->
                this.targetCallToReplace = call
                this.replaceArgName = arg
                val expressionCode = visit(expression)
                this.targetCallToReplace = null

                "$type $name = $expressionCode;\n$rest"
            }
        } else {
            val expressionCode = visit(expression)
            "$type $name = $expressionCode;\n$rest"
        }
    }

    override fun visitVariableAssignment(context: MiniKotlinParser.VariableAssignmentContext): String {
        val name = context.IDENTIFIER().text
        val rest = this.currentRestCode
        val expression = context.expression()

        val call = extractInnerFunctionCall(expression)

        return if (call != null) {
            generateCPSCall(call) { arg ->
                this.targetCallToReplace = call
                this.replaceArgName = arg
                val expressionCode = visit(expression)
                this.targetCallToReplace = null

                "$name = $expressionCode;\n$rest"
            }
        } else {
            "$name = ${visit(expression)};\n$rest"
        }
    }

    override fun visitReturnStatement(context: MiniKotlinParser.ReturnStatementContext): String {
        val expression = context.expression()

        if (expression == null) {
            return "if (__continuation != null) {\n    __continuation.accept(null);\n}\nreturn;"
        }

        val call = extractInnerFunctionCall(expression)
        return if (call != null) {
            generateCPSCall(call) { arg ->
                this.targetCallToReplace = call
                this.replaceArgName = arg
                val expressionCode = visit(expression)
                this.targetCallToReplace = null

                "if (__continuation != null) {\n    __continuation.accept($expressionCode);\n}\nreturn;"
            }
        } else {
            val expressionCode = visit(expression)
            "if (__continuation != null) {\n    __continuation.accept($expressionCode);\n}\nreturn;"
        }
    }

    override fun visitIfStatement(context: MiniKotlinParser.IfStatementContext): String {
        val conditionNode = visit(context.expression())
        val outerRest = this.currentRestCode

        var trueCode = outerRest
        val thenStmts = context.block(0)?.statement() ?: emptyList()

        for (i in thenStmts.indices.reversed()) {
            this.currentRestCode = trueCode
            trueCode = visit(thenStmts[i])
        }

        var falseCode = outerRest
        if (context.block().size > 1) {
            val elseStmts = context.block(1)?.statement() ?: emptyList()

            for (index in elseStmts.indices.reversed()) {
                this.currentRestCode = falseCode
                falseCode = visit(elseStmts[index])
            }
        }

        return """
            if ($conditionNode) {
                ${trueCode.prependIndent("    ")}
            } else {
                ${falseCode.prependIndent("    ")}
            }
        """.trimIndent()
    }

    override fun visitWhileStatement(context: MiniKotlinParser.WhileStatementContext): String {
        val conditionNode = visit(context.expression())
        val loopName = nextLoopName()

        var bodyCode = "this.run();"
        val bodyStatements = context.block().statement() ?: emptyList()

        for (index in bodyStatements.indices.reversed()) {
            this.currentRestCode = bodyCode
            bodyCode = visit(bodyStatements[index])
        }

        val outerRest = this.currentRestCode

        return """
            class $loopName {
                public void run() {
                    if ($conditionNode) {
                        ${bodyCode.prependIndent("    ")}
                    } else {
                        ${outerRest.prependIndent("    ")}
                    }
                }
            }
            new $loopName().run();
        """.trimIndent()
    }

    override fun visitFunctionCallExpr(context: MiniKotlinParser.FunctionCallExprContext): String {
        if (context === targetCallToReplace) {
            return replaceArgName
        }

        var funcName = context.IDENTIFIER().text
        if (funcName == "println" || funcName == "print") {
            funcName = "Prelude.$funcName"
        }

        val arguments = context.argumentList()?.expression() ?: emptyList()
        val argsStr = arguments.joinToString(", ") { visit(it) }

        return "$funcName($argsStr)"
    }

    override fun visitIdentifierExpr(context: MiniKotlinParser.IdentifierExprContext): String {
        return context.text
    }

    override fun visitIntLiteral(context: MiniKotlinParser.IntLiteralContext): String {
        return context.text
    }

    override fun visitStringLiteral(context: MiniKotlinParser.StringLiteralContext): String {
        return context.text
    }

    override fun visitBoolLiteral(context: MiniKotlinParser.BoolLiteralContext): String {
        return context.text
    }

    override fun visitParenExpr(context: MiniKotlinParser.ParenExprContext): String {
        return "(${visit(context.expression())})"
    }

    override fun visitNotExpr(context: MiniKotlinParser.NotExprContext): String {
        return "!(${visit(context.expression())})"
    }

    override fun visitAddSubExpr(context: MiniKotlinParser.AddSubExprContext): String {
        return "(${visit(context.expression(0))} ${context.getChild(1).text} ${visit(context.expression(1))})"
    }

    override fun visitMulDivExpr(context: MiniKotlinParser.MulDivExprContext): String {
        return "(${visit(context.expression(0))} ${context.getChild(1).text} ${visit(context.expression(1))})"
    }

    override fun visitComparisonExpr(context: MiniKotlinParser.ComparisonExprContext): String {
        return "(${visit(context.expression(0))} ${context.getChild(1).text} ${visit(context.expression(1))})"
    }

    override fun visitEqualityExpr(context: MiniKotlinParser.EqualityExprContext): String {
        return "(${visit(context.expression(0))} ${context.getChild(1).text} ${visit(context.expression(1))})"
    }

    override fun visitAndExpr(context: MiniKotlinParser.AndExprContext): String {
        return "(${visit(context.expression(0))} && ${visit(context.expression(1))})"
    }

    override fun visitOrExpr(context: MiniKotlinParser.OrExprContext): String {
        return "(${visit(context.expression(0))} || ${visit(context.expression(1))})"
    }

    private fun extractInnerFunctionCall(expression: ParseTree?): MiniKotlinParser.FunctionCallExprContext? {
        if (expression == null) {
            return null
        }

        if (expression is MiniKotlinParser.FunctionCallExprContext) {
            return expression
        }

        for (i in 0 until expression.childCount) {
            val call = extractInnerFunctionCall(expression.getChild(i))
            if (call != null) return call
        }

        return null
    }

    private fun generateCPSCall(
        call: MiniKotlinParser.FunctionCallExprContext,
        bodyGenerator: (String) -> String
    ): String {
        var funcName = call.IDENTIFIER().text
        if (funcName == "println" || funcName == "print") {
            funcName = "Prelude.$funcName"
        }

        val argStrs = call.argumentList()?.expression()?.map { visit(it) } ?: emptyList()
        val commaSplit = if (argStrs.isEmpty()) {
            ""
        } else {
            argStrs.joinToString(", ") + ", "
        }

        val argName = nextArgName()
        val innerBodyStr = bodyGenerator(argName)

        return """
            $funcName($commaSplit($argName) -> {
                ${innerBodyStr.prependIndent("    ")}
            });
        """.trimIndent()
    }
}