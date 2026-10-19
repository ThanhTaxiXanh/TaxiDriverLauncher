import { markdownToHtml } from "satteri";
import { describe, expect, it } from "vitest";
import satteriRepoLinks, {
    rewriteRepoHref,
    type RepoLinksOptions,
} from "./satteri-repo-links";

const options: RepoLinksOptions = {
    repoBlobBase:
        "https://github.com/seijikohara/femto-car-launcher/blob/main/",
    routes: {
        "README.md": "/",
        "PRIVACY.md": "/privacy/",
        "TERMS.md": "/terms/",
    },
};

describe("rewriteRepoHref", () => {
    it("maps repository Markdown the site renders to its root-relative route, keeping fragments", () => {
        expect(rewriteRepoHref("PRIVACY.md", options)).toBe("/privacy/");
        expect(rewriteRepoHref("TERMS.md#contact", options)).toBe(
            "/terms/#contact",
        );
        expect(rewriteRepoHref("README.md", options)).toBe("/");
    });

    it("sends other relative repository paths to GitHub", () => {
        expect(rewriteRepoHref("LICENSE", options)).toBe(
            "https://github.com/seijikohara/femto-car-launcher/blob/main/LICENSE",
        );
        expect(
            rewriteRepoHref("app/src/test/resources/README.md#map", options),
        ).toBe(
            "https://github.com/seijikohara/femto-car-launcher/blob/main/app/src/test/resources/README.md#map",
        );
    });

    it("leaves root-relative, absolute, protocol-relative, mailto and anchor links to other plugins", () => {
        for (const href of [
            "/terms/",
            "https://policies.google.com/privacy",
            "//example.com/x",
            "mailto:someone@example.com",
            "#data-the-app-accesses",
        ]) {
            expect(rewriteRepoHref(href, options)).toBeUndefined();
        }
    });
});

describe("satteriRepoLinks", () => {
    // Runs the real satteri pipeline rather than calling `visit` with a fake
    // HastVisitorContext: the plugin is then exercised through the same
    // context Astro gives it, with no partial stand-in to keep in sync.
    it("rewrites the href of an anchor element through the plugin context", async () => {
        const { html } = await markdownToHtml("[Privacy Policy](PRIVACY.md)", {
            hastPlugins: [satteriRepoLinks(options)],
        });
        expect(html).toContain('<a href="/privacy/">Privacy Policy</a>');
    });
});
