package com.example.vibrationmonitor;

import java.util.*;
import java.util.regex.*;

/** Pure presentation translation. Never use this class to translate stored keys or commands. */
public final class TextCatalog {
    private static final Pattern FORMAT = Pattern.compile("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]");
    private final Map<String,String[]> exact;
    private final List<Template> templates = new ArrayList<>();
    private final Map<String,String[]> fragments = new HashMap<>();
    private Pattern fragmentPattern;

    public TextCatalog(Map<String,String[]> entries) {
        exact = new LinkedHashMap<>(entries);
        List<String> keys = new ArrayList<>(entries.keySet());
        Collections.sort(keys, (a,b) -> Integer.compare(b.length(), a.length()));
        List<String> terms = new ArrayList<>();
        for (String key : keys) {
            if (key.isEmpty()) continue;
            if (FORMAT.matcher(key).find()) { templates.add(new Template(key, entries.get(key))); continue; }
            // Short labels translate only when they are the complete value, not inside user data.
            if (key.length() < 4 || key.indexOf('\n') >= 0 || key.startsWith("http")) continue;
            fragments.put(key, entries.get(key));
            boolean left = Character.isLetterOrDigit(key.charAt(0)) && key.charAt(0) < 128;
            boolean right = Character.isLetterOrDigit(key.charAt(key.length()-1)) && key.charAt(key.length()-1) < 128;
            terms.add((left ? "(?<![A-Za-z0-9_])" : "") + Pattern.quote(key) + (right ? "(?![A-Za-z0-9_])" : ""));
        }
        if (!terms.isEmpty()) fragmentPattern = Pattern.compile(String.join("|", terms));
    }
    private static String target(String[] v, int language) {
        return v[Math.max(0, Math.min(2, language))];
    }
    public String translate(CharSequence input, int language) {
        if (input == null) return "";
        String raw = input.toString();
        String[] e = exact.get(raw);
        if (e != null) return target(e,language);
        for (Template t : templates) {
            Matcher m = t.pattern.matcher(raw);
            if (m.matches()) return t.render(m,language);
        }
        if(raw.indexOf('\n')>=0) {
            String[] lines=raw.split("\n",-1);StringBuilder joined=new StringBuilder();
            for(int i=0;i<lines.length;i++){if(i>0)joined.append('\n');joined.append(translate(lines[i],language));}
            return joined.toString();
        }
        if (fragmentPattern == null) return raw;
        Matcher m=fragmentPattern.matcher(raw);
        StringBuffer out = new StringBuffer();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(target(fragments.get(m.group()),language)));
        m.appendTail(out);
        return out.toString();
    }
    private static final class Template {
        final Pattern pattern;
        final String[] values;
        Template(String source, String[] values) {
            this.values=values;
            Matcher m=FORMAT.matcher(source); int end=0;
            StringBuilder re=new StringBuilder("^");
            while(m.find()) {
                re.append(Pattern.quote(source.substring(end,m.start())));
                re.append("%%".equals(m.group()) ? "%" : "(.*?)"); end=m.end();
            }
            re.append(Pattern.quote(source.substring(end))).append("$");
            pattern=Pattern.compile(re.toString());
        }
        String render(Matcher captured,int language) {
            String translated=target(values,language);
            Matcher m=FORMAT.matcher(translated); StringBuffer out=new StringBuffer(); int index=1;
            while(m.find()) {
                String v;
                if("%%".equals(m.group())) v="%";
                else { v=index<=captured.groupCount() ? captured.group(index) : m.group(); index++; }
                m.appendReplacement(out,Matcher.quoteReplacement(v));
            }
            m.appendTail(out); return out.toString();
        }
    }
}
