package de.berlios.vch.parser.sf;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.felix.ipojo.annotations.Component;
import org.apache.felix.ipojo.annotations.Provides;
import org.apache.felix.ipojo.annotations.Requires;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.osgi.service.log.LogService;

import com.sun.syndication.feed.synd.SyndEnclosure;
import com.sun.syndication.feed.synd.SyndEntry;
import com.sun.syndication.feed.synd.SyndFeed;
import com.sun.syndication.io.impl.Base64;

import de.berlios.vch.http.client.HttpResponse;
import de.berlios.vch.http.client.HttpUtils;
import de.berlios.vch.parser.HtmlParserUtils;
import de.berlios.vch.parser.IOverviewPage;
import de.berlios.vch.parser.IVideoPage;
import de.berlios.vch.parser.IWebPage;
import de.berlios.vch.parser.IWebParser;
import de.berlios.vch.parser.OverviewPage;
import de.berlios.vch.parser.VideoPage;
import de.berlios.vch.rss.RssParser;

@Component
@Provides
public class SfVideoportalParser implements IWebParser {
    public static final String CHARSET = "UTF-8";

    public static final String ID = SfVideoportalParser.class.getName();

    private final String BASE_URI = "https://www.srf.ch";
    private final String START_PAGE = BASE_URI + "/podcasts";

    @Requires
    private LogService logger;

    @Override
    public IOverviewPage getRoot() throws Exception {
        Map<String, IOverviewPage> AbisZ = new HashMap<String, IOverviewPage>();
        OverviewPage page = new OverviewPage();
        page.setParser(ID);
        page.setTitle(getTitle());
        page.setUri(new URI("vchpage://localhost/" + getId()));

        String content = HttpUtils.get(START_PAGE, null, CHARSET);

        Elements sections = HtmlParserUtils.getTags(content, "div#letters div[data-letter-group] ul > li.shows");
        for (int i = 0; i < sections.size(); i++) {
            Element section = sections.get(i);
            Element h3 = section.getElementsByTag("h3").first();
            if (!"tv".equals(h3.attr("class"))) {
                // this is not a video feed -> ignore
                continue;
            }

            String title = h3.text();
            OverviewPage programPage = new OverviewPage();
            programPage.setParser(ID);
            programPage.setTitle(title);

            // try HD first
            String uri = "";
            Elements feed = section.getElementsByAttribute("data-url-hd");
            if(!feed.isEmpty()) {
                uri = feed.first().attr("data-url-hd").trim();
            } else {
                logger.log(LogService.LOG_DEBUG, "HD not found for " + programPage.getTitle());
                // fall back to SD
                feed = section.getElementsByAttribute("data-url-sd");
                if(!feed.isEmpty()) {
                    uri = feed.first().attr("data-url-sd").trim();
                } else {
                    logger.log(LogService.LOG_DEBUG, "SD not found for " + programPage.getTitle());
                    // still no feed found -> ignore
                    continue;
                }
            }

            try {
                programPage.setUri(new URI(uri));

                String firstCharacter = title.substring(0, 1).toUpperCase();
                firstCharacter = firstCharacter.replaceAll("[0-9]", "0-9");
                IOverviewPage sectionPage = AbisZ.get(firstCharacter);
                if (sectionPage == null) {
                    sectionPage = new OverviewPage();
                    sectionPage.setParser(ID);
                    sectionPage.setTitle(firstCharacter);
                    sectionPage.setUri(new URI("sf://section/" + Base64.encode(title)));
                    AbisZ.put(firstCharacter, sectionPage);
                    page.getPages().add(sectionPage);
                }
                sectionPage.getPages().add(programPage);
            } catch(URISyntaxException e) {
                logger.log(LogService.LOG_WARNING, "Couldn't parse feed at [" + uri + "]", e);
            }
        }
        return page;
    }

    @Override
    public String getTitle() {
        return "SF Videoportal";
    }

    @Override
    public IWebPage parse(IWebPage page) throws Exception {
        if (page instanceof IOverviewPage) {
            if ("sf".equals(page.getUri().getScheme())) {
                return page;
            } else {
                logger.log(LogService.LOG_DEBUG, "Parsing program page at " + page.getUri());
                IOverviewPage programPage = (IOverviewPage) page;

                HttpResponse response = HttpUtils.getResponse(page.getUri().toString(), null, CHARSET);
                while(response.getHeader().containsKey("Location")) {
                    response = HttpUtils.getResponse(response.getHeader().get("Location").get(0), null, CHARSET);
                }
                System.out.println(response.getHeader());
                String rssContent = response.getContent();
                SyndFeed feed = RssParser.parse(rssContent);
                for (Iterator<?> iterator = feed.getEntries().iterator(); iterator.hasNext();) {
                    SyndEntry entry = (SyndEntry) iterator.next();
                    VideoPage video = new VideoPage();
                    video.setParser(getId());
                    video.setTitle(entry.getTitle());
                    video.setDescription(entry.getDescription().getValue());
                    Calendar pubCal = Calendar.getInstance();
                    pubCal.setTime(entry.getPublishedDate());
                    video.setPublishDate(pubCal);
                    video.setVideoUri(new URI(((SyndEnclosure) entry.getEnclosures().get(0)).getUrl()));
                    if (entry.getLink() != null) {
                        video.setUri(new URI(entry.getLink()));
                    } else {
                        video.setUri(video.getVideoUri());
                    }

                    // look, if we have a duration in the foreign markup
                    @SuppressWarnings("unchecked")
                    List<org.jdom.Element> fm = (List<org.jdom.Element>) entry.getForeignMarkup();
                    for (org.jdom.Element element : fm) {
                        if ("duration".equals(element.getName())) {
                            try {
                                video.setDuration(Long.parseLong(element.getText()));
                            } catch (Exception e) {
                            }
                        }
                    }

                    programPage.getPages().add(video);
                }
            }
        } else if (page instanceof IVideoPage) {
            logger.log(LogService.LOG_DEBUG, "Parsing video page at " + page.getUri());
            // parse video page
            // parseVideoPage((IVideoPage) page);
            return page;
        }

        return page;
    }

    @Override
    public String getId() {
        return ID;
    }
}