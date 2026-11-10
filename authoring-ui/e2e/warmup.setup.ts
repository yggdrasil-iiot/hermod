import { test as setup, expect } from "@playwright/test";

// This setup project runs AFTER webServer is up (setup projects run as tests,
// not as globalSetup) so it can actually reach Vite. By depending on "warmup",
// the "chromium" project waits for this to complete, ensuring the first
// recorded spec (01-compose) does not capture a blank cold-start frame.
setup("warm up vite dev server", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("body")).toContainText(/Compose|Console|Hermod/i, {
    timeout: 60_000,
  });
});
