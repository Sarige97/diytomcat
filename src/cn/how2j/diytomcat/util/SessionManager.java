package cn.how2j.diytomcat.util;

import cn.how2j.diytomcat.http.Request;
import cn.how2j.diytomcat.http.Response;
import cn.how2j.diytomcat.http.StandardSession;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.SecureUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import javax.servlet.ServletContext;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.*;

public class SessionManager {
    private static Map<String, StandardSession> sessionMap = new HashMap<>();
    private static int defaultTimeout = getTimeout();

    //类加载后启动检测session失效的线程
    static {
        startSessionOutdateCheckThread();
    }

    /**
     * 获取session的逻辑
     * 如果浏览器没有传递 jsessionid 过来，那么就创建一个新的session
     * 如果浏览器传递过来的 jsessionid 无效，那么也创建一个新的 sessionId
     * 否则就使用现成的session, 并且修改它的lastAccessedTime， 以及创建对应的 cookie
     *
     * @param jsessionid 浏览器传递过来的jsessionid
     * @param request    HttpRequest对象
     * @param response   HttpResponse对象
     * @return 根据情况返回一个HttpSession对象
     */
    public static HttpSession getSession(String jsessionid, Request request, Response response) {
        if (null == jsessionid) {
            return newSession(request, response);
        } else {
            StandardSession currentSession = sessionMap.get(jsessionid);
            if (null == currentSession) {
                return newSession(request, response);
            } else {
                currentSession.setLastAccessedTime(System.currentTimeMillis());
                createCookieBySession(currentSession, request, response);
                return currentSession;
            }
        }
    }

    /**
     * 根据session创建关于JSESSIONID的cookie
     *
     * @param session  HttpSession对象
     * @param request  HttpRequest对象
     * @param response HttpResponse对象
     */
    private static void createCookieBySession(HttpSession session, Request request, Response response) {
        Cookie cookie = new Cookie("JSESSIONID", session.getId());
        cookie.setMaxAge(session.getMaxInactiveInterval());
        cookie.setPath(request.getContext().getPath());
        response.addCookie(cookie);
    }

    /**
     * 创建session，并将创建好的session加入sessionMap，并创建JSESSION的Cookie加入response对象
     *
     * @param request  HttpRequest对象
     * @param response HttpResponse对象
     * @return 返回创建的session
     */
    private static HttpSession newSession(Request request, Response response) {
        ServletContext servletContext = request.getServletContext();
        String sid = generateSessionId();
        StandardSession session = new StandardSession(sid, servletContext);
        session.setMaxInactiveInterval(defaultTimeout);
        sessionMap.put(sid, session);
        createCookieBySession(session, request, response);
        return session;
    }

    /**
     * 解析web.xml获取session的超时时间
     *
     * @return 返回session超时时间
     */
    private static int getTimeout() {
        int defaultResult = 30;
        try {
            Document d = Jsoup.parse(Constant.webXmlFile, "utf-8");
            Elements es = d.select("session-config session-timeout");
            if (es.isEmpty())
                return defaultResult;
            return Convert.toInt(es.get(0).text());
        } catch (IOException e) {
            return defaultResult;
        }
    }

    /**
     * 从sessionMap里根据 lastAccessedTime 筛选出过期的 jsessionids ,然后把他们从 sessionMap 里去掉
     */
    private static void checkOutDateSession() {
        Set<String> jsessionids = sessionMap.keySet();
        List<String> outdateJessionIds = new ArrayList<>();

        for (String jsessionid : jsessionids) {
            StandardSession session = sessionMap.get(jsessionid);
            long interval = System.currentTimeMillis() - session.getLastAccessedTime();
            if (interval > session.getMaxInactiveInterval() * 1000)
                outdateJessionIds.add(jsessionid);
        }

        for (String jsessionid : outdateJessionIds) {
            sessionMap.remove(jsessionid);
        }
    }

    /**
     * 启动检查session超时的线程，每三十秒执行一次
     */
    private static void startSessionOutdateCheckThread() {
        new Thread(() -> {
            while (true) {
                checkOutDateSession();
                ThreadUtil.sleep(1000 * 30);
            }
        }).start();

    }

    /**
     * 生成一个SESSIONID
     *
     * @return 返回一个SESSIONID
     */
    public static synchronized String generateSessionId() {
        String result = null;
        byte[] bytes = RandomUtil.randomBytes(16);
        result = new String(bytes);
        result = SecureUtil.md5(result);
        result = result.toUpperCase();
        return result;
    }
}
