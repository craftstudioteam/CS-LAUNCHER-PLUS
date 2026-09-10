package net.kdt.pojavlaunch.modloaders;

import android.util.Log;

import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.HtmlNode;
import org.htmlcleaner.TagNode;
import org.htmlcleaner.TagNodeVisitor;

import java.io.IOException;

import net.kdt.pojavlaunch.utils.DownloadUtils;

/**
 * Second leg of an OptiFine download: the interstitial page (adloadx) that sits
 * between the version list and the actual file, turned into the URL that really
 * serves the jar.
 *
 * It deliberately does NOT use {@code HtmlCleaner.clean(URL)}. That path opens
 * a raw {@code URLConnection} whose User-Agent is {@code Java/1.8.0_x}, and
 * OptiFine's Cloudflare answers that with "403 error code: 1010" — the page the
 * scraper then fails to read. Fetching through DownloadUtils gives the request
 * the same identity (and the same session cookie jar) as the rest of the
 * download, which is what the host expects.
 */
public class OFDownloadPageScraper implements TagNodeVisitor {
    private static final String TAG = "OFScraper";

    public static String run(String urlInput) throws IOException {
        return new OFDownloadPageScraper().runInner(urlInput);
    }

    private String mDownloadFullUrl;

    private String runInner(String url) throws IOException {
        String html = DownloadUtils.fetchString(url, "https://optifine.net/downloads");
        if (html == null || html.length() < 64) {
            throw new IOException("Empty or blocked response from " + url);
        }
        // A blocked/challenge page must never be parsed into a "download url".
        if (html.indexOf("<a ") < 0 && html.toLowerCase().contains("error")) {
            throw new IOException("OptiFine refused the download page for " + url);
        }
        HtmlCleaner htmlCleaner = new HtmlCleaner();
        Object root = htmlCleaner.clean(html);
        if (!(root instanceof TagNode)) {
            Log.w(TAG, "Could not parse the download page");
            return null;
        }
        ((TagNode) root).traverse(this);
        return mDownloadFullUrl;
    }

    @Override
    public boolean visit(TagNode parentNode, HtmlNode htmlNode) {
        if (isDownloadUrl(parentNode, htmlNode)) {
            TagNode tagNode = (TagNode) htmlNode;
            String href = tagNode.getAttributeByName("href");
            if (href == null || href.isEmpty()) return true;
            if (href.startsWith("https://") || href.startsWith("http://")) {
                this.mDownloadFullUrl = href;
            } else {
                this.mDownloadFullUrl = "https://optifine.net/" + href;
            }
            return false;
        }
        return true;
    }

    public boolean isDownloadUrl(TagNode parentNode, HtmlNode htmlNode) {
        if (!(htmlNode instanceof TagNode)) return false;
        if (parentNode == null) return false;
        TagNode tagNode = (TagNode) htmlNode;
        boolean inDownloadSpan = "span".equals(parentNode.getName())
                && "Download".equals(parentNode.getAttributeByName("id"));
        if (!inDownloadSpan) {
            // The page also carries a plain .downloadButton link to the same URL;
            // accept it, otherwise a markup reshuffle means "no download".
            inDownloadSpan = "div".equals(parentNode.getName())
                    && "downloadButton".equals(parentNode.getAttributeByName("class"));
        }
        if (!inDownloadSpan) return false;
        if (!"a".equals(tagNode.getName())) return false;
        String onclick = tagNode.getAttributeByName("onclick");
        // onDownload() is only a 1s ad redirect for humans; a bot either has it
        // or has the href already. Requiring it was a self-inflicted failure.
        return onclick == null || "onDownload()".equals(onclick);
    }
}
