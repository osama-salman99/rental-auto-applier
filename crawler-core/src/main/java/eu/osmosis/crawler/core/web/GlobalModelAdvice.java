package eu.osmosis.crawler.core.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalModelAdvice {

    private final Environment env;

    public GlobalModelAdvice(Environment env) {
        this.env = env;
    }

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }

    @ModelAttribute("appName")
    public String appName() {
        String displayName = env.getProperty("crawler.app.display-name");
        if (displayName != null && !displayName.isBlank()) return displayName;
        String appName = env.getProperty("spring.application.name");
        return appName != null ? appName : "Crawler";
    }
}
