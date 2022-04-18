package eu.bebendorf.purejavaparser.parser;

import eu.bebendorf.purejavaparser.PureJavaParser;
import eu.bebendorf.purejavaparser.ast.*;
import eu.bebendorf.purejavaparser.token.Token;
import eu.bebendorf.purejavaparser.token.TokenStack;
import eu.bebendorf.purejavaparser.token.TokenType;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

@AllArgsConstructor
public class ExpressionParser {

    PureJavaParser parser;

    public Expression parseExpression(TokenStack stack) throws UnexpectedTokenException {
        TokenStack stackCopy = stack.trim().clone();
        UnexpectedTokenException ex = null;
        try {
            Expression expression = parseLambdaExpression(stackCopy);
            stack.copyFrom(stackCopy);
            return expression;
        } catch (UnexpectedTokenException nextEx) {
            ex = nextEx;
            stackCopy = stack.clone();
        }
        try {
            Expression expression = resolveGroup(stackCopy);
            expression = tryExpandAccessExpression(stackCopy, expression);
            expression = tryExpandFactorizationExpression(stackCopy, expression);
            expression = tryExpandAdditiveExpression(stackCopy, expression);
            expression = tryExpandComparisonExpression(stackCopy, expression);
            stack.copyFrom(stackCopy);
            return expression;
        } catch (UnexpectedTokenException nextEx) {
            throw nextEx.getToken().getPos() > ex.getToken().getPos() ? nextEx : ex;
        }
    }

    private Expression parseLambdaExpression(TokenStack stack) throws UnexpectedTokenException {
        TokenStack stackCopy = stack.trim().clone();
        ParameterList parameters;
        if(stackCopy.peek().getType() == TokenType.GROUP_START) {
            parameters = parseParameterList(stackCopy);
        } else if(stackCopy.peek().getType() == TokenType.NAME) {
            Variable variable = parser.getGeneralParser().parseVariable(stackCopy);
            List<ParameterDefinition> definitions = new ArrayList<>();
            definitions.add(new ParameterDefinition(variable));
            parameters = new ParameterList(definitions);
        } else {
            throw new UnexpectedTokenException(stackCopy.pop());
        }
        if(stackCopy.trim().peek().getType() != TokenType.LAMBDA_ARROW)
            throw new UnexpectedTokenException(stackCopy.pop());
        stackCopy.pop();
        StatementBlock body;
        if(stackCopy.trim().peek().getType() == TokenType.OPEN_CURLY_BRACKET) {
            body = parser.getStatementParser().parseStatementBlock(stackCopy);
        } else {
            Expression expression = parseExpression(stackCopy);
            List<Statement> statements = new ArrayList<>();
            statements.add(new Return(expression));
            body = new StatementBlock(statements);
        }
        stack.copyFrom(stackCopy);
        return new Lambda(parameters, body);
    }

    private ParameterList parseParameterList(TokenStack stack) throws UnexpectedTokenException {
        if(stack.trim().pop().getType() != TokenType.GROUP_START)
            return null;
        List<ParameterDefinition> parameters = new ArrayList<>();
        while (stack.trim().peek().getType() != TokenType.GROUP_END) {
            if(parameters.size() > 0) {
                Token t = stack.trim().pop();
                if(t.getType() != TokenType.SEPERATOR)
                    throw new UnexpectedTokenException(t);
            }
            ParameterDefinition param = parseParameterDefinition(stack);
            parameters.add(param);
        }
        stack.pop();
        return new ParameterList(parameters);
    }

    private ParameterDefinition parseParameterDefinition(TokenStack stack) throws UnexpectedTokenException {
        Variable variable = parser.getGeneralParser().parseVariable(stack);
        return new ParameterDefinition(variable);
    }

    private Expression resolveGroup(TokenStack stack) throws UnexpectedTokenException {
        if(stack.trim().peek().getType() == TokenType.GROUP_START) {
            stack.pop();
            Expression expression = parseExpression(stack);
            if(stack.trim().peek().getType() != TokenType.GROUP_END)
                throw new UnexpectedTokenException(stack.pop());
            stack.pop();
            return new ExpressionGroup(expression);
        } else {
            return parseTerminalExpression(stack);
        }
    }

    private Expression tryExpandComparisonExpression(TokenStack stack, Expression left) throws UnexpectedTokenException {
        if(stack.peek().getType() == TokenType.COMPARISON_OP) {
            Token t = stack.pop();
            Expression right = resolveGroup(stack);
            right = tryExpandAccessExpression(stack, right);
            right = tryExpandFactorizationExpression(stack, right);
            right = tryExpandAdditiveExpression(stack, right);
            return tryExpandComparisonExpression(stack, new Comparison(t.getValue(), left, right));
        }
        return left;
    }

    private Expression tryExpandAdditiveExpression(TokenStack stack, Expression left) throws UnexpectedTokenException {
        if(stack.peek().getType() == TokenType.ARITHMETIC_OP) {
            Token t = stack.peek();
            switch (t.getValue()) {
                case "+": {
                    stack.pop();
                    Expression right = resolveGroup(stack);
                    right = tryExpandAccessExpression(stack, right);
                    right = tryExpandFactorizationExpression(stack, right);
                    return tryExpandAdditiveExpression(stack, new Addition(left, right));
                }
                case "-": {
                    stack.pop();
                    Expression right = resolveGroup(stack);
                    right = tryExpandAccessExpression(stack, right);
                    right = tryExpandFactorizationExpression(stack, right);
                    return tryExpandAdditiveExpression(stack, new Subtraction(left, right));
                }
            }
        }
        return left;
    }

    private Expression tryExpandFactorizationExpression(TokenStack stack, Expression left) throws UnexpectedTokenException {
        if(stack.peek().getType() == TokenType.ARITHMETIC_OP) {
            Token t = stack.peek();
            switch (t.getValue()) {
                case "*": {
                    stack.pop();
                    Expression right = resolveGroup(stack);
                    right = tryExpandAccessExpression(stack, right);
                    return tryExpandFactorizationExpression(stack, new Multiplication(left, right));
                }
                case "/": {
                    stack.pop();
                    Expression right = resolveGroup(stack);
                    right = tryExpandAccessExpression(stack, right);
                    return tryExpandFactorizationExpression(stack, new Division(left, right));
                }
                case "%": {
                    stack.pop();
                    Expression right = resolveGroup(stack);
                    right = tryExpandAccessExpression(stack, right);
                    return tryExpandFactorizationExpression(stack, new Modulo(left, right));
                }
            }
        }
        return left;
    }

    private Expression tryExpandAccessExpression(TokenStack stack, Expression left) throws UnexpectedTokenException {
        TokenStack stackCopy = stack.trim().clone();
        if(stackCopy.peek().getType() == TokenType.PROPERTY_ACCESS) {
            stackCopy.pop();
            if(stackCopy.trim().peek().getType() == TokenType.NAME) {
                Expression newTerminal = new PropertyAccess(left, stackCopy.pop().getValue());
                stack.copyFrom(stackCopy);
                return tryExpandAccessExpression(stack, newTerminal);
            } else {
                throw new UnexpectedTokenException(stackCopy.pop());
            }
        }
        if(stackCopy.peek().getType() == TokenType.ARRAY_START) {
            stackCopy.pop();
            Expression index = parseExpression(stackCopy);
            if(stackCopy.trim().peek().getType() != TokenType.ARRAY_END)
                throw new UnexpectedTokenException(stackCopy.pop());
            Expression newTerminal = new ArrayAccess(left, index);
            stack.copyFrom(stackCopy);
            return tryExpandAccessExpression(stack, newTerminal);
        }
        if(stackCopy.peek().getType() == TokenType.GROUP_START) {
            ArgumentList argumentList = parseArgumentList(stackCopy);
            Expression newTerminal = new MethodCall(left, argumentList);
            stack.copyFrom(stackCopy);
            return tryExpandAccessExpression(stack, newTerminal);
        }
        return left;
    }

    private Expression parseTerminalExpression(TokenStack stack) throws UnexpectedTokenException {
        if(stack.peek().getType() == TokenType.NULL_LITERAL) {
            stack.pop();
            return NullLiteral.INSTANCE;
        }
        if(stack.peek().getType() == TokenType.BOOLEAN_LITERAL) {
            String value = stack.pop().getValue();
            return value.equals("true") ? BooleanLiteral.TRUE : BooleanLiteral.FALSE;
        }
        if(stack.peek().getType() == TokenType.DECIMAL_INT_LITERAL) {
            BigInteger value = new BigInteger(stack.pop().getValue());
            return new IntegerLiteral(value);
        }
        if(stack.peek().getType() == TokenType.HEX_INT_LITERAL) {
            BigInteger value = new BigInteger(stack.pop().getValue().substring(2), 16);
            return new IntegerLiteral(value);
        }
        if(stack.peek().getType() == TokenType.BINARY_INT_LITERAL) {
            BigInteger value = new BigInteger(stack.pop().getValue().substring(2), 2);
            return new IntegerLiteral(value);
        }
        if(stack.peek().getType() == TokenType.DECIMAL_FLOAT_LITERAL) {
            BigDecimal value = new BigDecimal(stack.pop().getValue());
            return new FloatLiteral(value);
        }
        if(stack.peek().getType() == TokenType.STRING_LITERAL) {
            String value = stack.pop().getValue();
            return new StringLiteral(unescapeStringLiteral(value.substring(1, value.length() - 1)));
        }
        if(stack.peek().getType() == TokenType.CHAR_LITERAL) {
            String value = stack.pop().getValue();
            return new CharLiteral(unescapeCharLiteral(value.substring(1, value.length() - 1)));
        }
        if(stack.peek().getType() == TokenType.NAME) {
            try {
                TokenStack stackCopy = stack.trim().clone();
                ClassLiteral classLiteral = parseClassLiteral(stackCopy);
                stack.copyFrom(stackCopy);
                return classLiteral;
            } catch (UnexpectedTokenException ex) {
                return new Variable(stack.pop().getValue());
            }
        }
        throw new UnexpectedTokenException(stack.pop());
    }

    private ClassLiteral parseClassLiteral(TokenStack stack) throws UnexpectedTokenException {
        TokenStack stackCopy = stack.trim().clone();
        if(stackCopy.peek().getType() != TokenType.NAME)
            throw new UnexpectedTokenException(stackCopy.pop());
        List<String> parts = new ArrayList<>();
        parts.add(stackCopy.pop().getValue());
        while (true) {
            if(stackCopy.trim().peek().getType() != TokenType.PROPERTY_ACCESS)
                throw new UnexpectedTokenException(stackCopy.pop());
            stackCopy.pop();
            if(stackCopy.trim().peek().getType() == TokenType.CLASS) {
                stackCopy.pop();
                stack.copyFrom(stackCopy);
                return new ClassLiteral(parts);
            }
            if(stackCopy.peek().getType() != TokenType.NAME)
                throw new UnexpectedTokenException(stackCopy.pop());
            parts.add(stackCopy.pop().getValue());
        }
    }

    private ArgumentList parseArgumentList(TokenStack stack) throws UnexpectedTokenException {
        if(stack.trim().peek().getType() != TokenType.GROUP_START)
            throw new UnexpectedTokenException(stack.pop());
        stack.pop();
        List<Expression> arguments = new ArrayList<>();
        while (stack.trim().peek().getType() != TokenType.GROUP_END) {
            if(arguments.size() > 0) {
                if(stack.trim().pop().getType() != TokenType.SEPERATOR)
                    throw new UnexpectedTokenException(stack.pop());
            }
            Expression arg = parseExpression(stack);
            arguments.add(arg);
        }
        stack.pop();
        return new ArgumentList(arguments);
    }

    private char unescapeCharLiteral(String s) {
        switch (s) {
            case "\\'":
                return '\'';
            case "\\n":
                return '\n';
            case "\\r":
                return '\r';
            case "\\t":
                return '\t';
            case "\\b":
                return '\b';
            case "\\f":
                return '\f';
            case "\\0":
                return '\0';
        }
        return s.charAt(0);
    }

    private String unescapeStringLiteral(String s) {
        return s
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\f", "\f")
                .replace("\\b", "\b")
                .replace("\\0", "\0");
    }

}