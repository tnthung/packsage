# Guide entry navigation checks

Use the minimal **Packsage** CurseForge profile. It contains Modonomicon 1.120.7 (with the Neo Vitae book), GuideME 21.1.19 (with Powah's guide), Immersive Engineering 12.4.2-194 and Actually Additions 1.3.26. The assistant builds, runs the automated tests and installs the candidate as `mods/packsage.jar` while Minecraft is closed. You don't need to run any Java, Gradle or terminal commands.

Expected behavior is defined in the [guide entry navigation specification](../specs/2026-10-21-guide-entry-navigation.md). Observed results go in the [validation record](../validation/2026-10-21-guide-entry-navigation.md); this checklist is not evidence that a check passed.

## Links open the cited destination

Open PackSage in a singleplayer world and start a new chat. Ask one question for each guide below, so the reply cites that guide. Click each link under the reply.

1. **Modonomicon:** ask about a topic from an unlocked entry in the installed Modonomicon book. The link opens the book at that entry's first page.
2. **GuideME:** ask about a topic that appears under a heading in Powah's guide. The reply can show two links: one for the page and one for the heading. The page link opens the page at the top. The heading link opens the same page scrolled to that heading.
3. **Immersive Engineering:** ask about a manual topic, such as the Engineer's Hammer. The link opens the Engineer's Manual at that entry, even when you aren't holding the manual.
4. **Actually Additions:** ask about a booklet topic, such as the Crystals entry. The link opens the Actually Additions booklet at that entry.

A link must never open a different entry or page, or a "not found" page. Note any useful reference that has no link.

## Saved links survive a restart

Close Minecraft completely, start the profile again, load the same world and open the same chat. Click every link from the previous section again. Each one must open the same destination as before.

## Locked entries refuse to open

In the Modonomicon book, find an entry that is visible but still locked (shown with a lock in the book), and ask about it. Its reply should have no link to that entry. If a saved link to an entry later becomes locked, clicking it must show an unavailable message instead of opening the entry.
