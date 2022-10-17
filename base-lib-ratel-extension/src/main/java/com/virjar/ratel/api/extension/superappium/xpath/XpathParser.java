package com.virjar.ratel.api.extension.superappium.xpath;

import android.util.LruCache;

import com.virjar.ratel.api.extension.superappium.xpath.model.XpathEvaluator;
import com.virjar.ratel.api.extension.superappium.xpath.parser.TokenQueue;
import com.virjar.ratel.api.extension.superappium.xpath.parser.XpathStateMachine;
import com.virjar.ratel.api.extension.superappium.xpath.exception.XpathSyntaxErrorException;


public class XpathParser {
    private String xpathStr;

    public String getXpathStr() {
        return xpathStr;
    }

    private TokenQueue tokenQueue;

    private static LruCache<String, XpathEvaluator> cache = new LruCache<>(128);

    public XpathEvaluator parse() throws XpathSyntaxErrorException {
        XpathStateMachine xpathStateMachine = new XpathStateMachine(tokenQueue);
        while (xpathStateMachine.getState() != XpathStateMachine.BuilderState.END) {
            xpathStateMachine.getState().parse(xpathStateMachine);
        }
        return xpathStateMachine.getEvaluator();
    }

    /**
     * no error代表调用放明确知道xpath没有语法错误,主动放弃检查,是一个方便的方法,但是如果表达式确实有语法错误,本方法跑出非法状态异常
     *
     * @param xpathStr xpath表达式
     * @return 由模型描述的xpath抽取器
     */
    public static XpathEvaluator compileNoError(String xpathStr) {
        try {
            return compile(xpathStr);
        } catch (XpathSyntaxErrorException e) {
            throw new IllegalStateException("parse xpath \"" + xpathStr + "\" failed", e);
        }
    }

    public XpathParser(String subXpath) {
        this.xpathStr = subXpath;
        tokenQueue = new TokenQueue(xpathStr);
    }

    public static XpathEvaluator compile(String xpathStr) throws XpathSyntaxErrorException {
        if (xpathStr == null) {
            throw new XpathSyntaxErrorException(0, "xpathStr can not be null");
        }
        XpathEvaluator xpathEvaluator = cache.get(xpathStr);
        if (xpathEvaluator == null) {
            xpathEvaluator = new XpathParser(xpathStr).parse();
            cache.put(xpathStr, xpathEvaluator);
            xpathEvaluator = cache.get(xpathStr);

        }
        return xpathEvaluator;
    }

}
