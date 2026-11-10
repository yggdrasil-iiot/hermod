import { test, expect } from "@playwright/test";

test("app shell loads", async ({ page }) => {
  await page.goto("/");
  // The app renders tab nav incl. Compose + Console; assert the shell mounted.
  await expect(page.locator("body")).toContainText(/Compose|Console|Hermod/i);
});
