import type { ReactNode } from "react";
import Link from "../components/Link";
import "./policies.css";

/** Contact for privacy requests and questions about the terms (also the auth email sender). */
export const CONTACT_EMAIL = "verifactai2026@gmail.com";

/** Change when either page changes in substance, and say what changed at the top of the page. */
const LAST_UPDATED = "October 1, 2026";

function PolicyPage({ id, title, lede, children }: { id: string; title: string; lede: ReactNode; children: ReactNode }) {
  return (
    <article className="card policy" aria-labelledby={`${id}-heading`}>
      <header className="policy-header">
        <h1 id={`${id}-heading`}>{title}</h1>
        <p className="muted small">Last updated {LAST_UPDATED}</p>
        <p className="policy-lede">{lede}</p>
      </header>
      {children}
    </article>
  );
}

const Mail = () => <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>;

export function PrivacyPage() {
  return (
    <PolicyPage
      id="privacy"
      title="Privacy Policy"
      lede="What VeriFact collects when you use it, why, who else sees it, how long we keep it, and how to get it deleted. We wrote it in plain language; if anything is unclear, email us."
    >
      <section>
        <h2>Who we are</h2>
        <p>
          VeriFact (verifact-blf.pages.dev) is an independent project that checks claims against published sources. It includes
          VeriFact, NewsFact, LegalFact and ResearchFact. For anything about your data, contact <Mail />.
        </p>
      </section>

      <section>
        <h2>What we collect</h2>
        <h3>When you use a checker</h3>
        <ul>
          <li>
            <strong>VeriFact checks:</strong> the report we produce, including up to 1,500 characters of the text you submitted, the
            text read from your image, or the transcript of your audio. We don&apos;t keep the image or audio file itself.
          </li>
          <li>
            <strong>NewsFact reviews:</strong> the article link or text you submit, the results and any notes you add.
          </li>
          <li>
            <strong>ResearchFact workspaces:</strong> your topic, saved sources and notes. If you upload a draft, we keep the text
            taken from it and the analysis, not the file.
          </li>
          <li>
            <strong>LegalFact:</strong> what you enter is used to produce your result and is not stored.
          </li>
          <li>
            <strong>Feedback:</strong> whether a report was helpful, the reason you picked, and any comment you write.
          </li>
        </ul>
        <p>
          You don&apos;t need an account for any of this, and these records aren&apos;t linked to your account. Don&apos;t submit
          passwords, ID numbers, health details or other private information about yourself or anyone else.
        </p>

        <h3>When you create an account</h3>
        <ul>
          <li>Your email address and, if you sign in with Google, your Google account&apos;s name and email.</li>
          <li>Your password, stored only as a secure hash by our sign-in provider. We never see it.</li>
          <li>When you created the account and when it last changed.</li>
        </ul>

        <h3>Automatically</h3>
        <ul>
          <li>
            Technical logs (such as time, page or service used, and error details) to keep the service running and to stop abuse. Our
            rate limiting uses your IP address briefly in memory.
          </li>
          <li>Anonymous visit statistics from Cloudflare Web Analytics, which doesn&apos;t use cookies or track you across sites.</li>
        </ul>

        <h3>In your browser</h3>
        <p>
          We save a few things on your device: your light/dark theme, your recent checks, which reports you&apos;ve rated, the keys
          that let you edit your NewsFact reviews and research workspaces, and your sign-in session. We don&apos;t use advertising or
          tracking cookies. Clearing your browser data removes them.
        </p>
      </section>

      <section>
        <h2>Why we use it</h2>
        <ul>
          <li>To run the checks you ask for and show you the results.</li>
          <li>To let you sign in, reset your password and manage your account.</li>
          <li>To find and fix mistakes, improve accuracy (for example, from feedback), and protect the service from abuse.</li>
        </ul>
        <p>We don&apos;t sell your data, show ads, or use your submissions to build advertising profiles.</p>
      </section>

      <section>
        <h2>Who else receives it</h2>
        <p>To work, VeriFact passes data to these services. Each handles it under its own privacy policy.</p>
        <ul>
          <li>
            <strong>OpenAI</strong> reads what you submit (text, images, article text) to find claims and weigh the evidence.
          </li>
          <li>
            <strong>Search and reference services</strong> (Tavily, Google, YouTube, Crossref, DataCite, OpenAlex and PubMed) receive
            search queries built from your submission, not your account details.
          </li>
          <li>
            <strong>Google Cloud Speech-to-Text</strong> receives audio you upload for transcription.
          </li>
          <li>
            <strong>Supabase</strong> stores our database and runs sign-in. <strong>Render</strong> runs our servers.{" "}
            <strong>Cloudflare</strong> serves the website. <strong>Gmail</strong> sends our account emails.
          </li>
        </ul>
        <p>
          Some of these services are outside your country, including in the United States, Singapore and Australia. We only share
          what each one needs. We&apos;ll also disclose data if the law requires it.
        </p>
      </section>

      <section>
        <h2>Who can see your results</h2>
        <p>
          Reports, NewsFact reviews and research workspaces have hard-to-guess links. Anyone you give the link to can read them, so
          share carefully.
        </p>
      </section>

      <section>
        <h2>How long we keep it</h2>
        <ul>
          <li>Research workspaces are deleted automatically 90 days after the last change.</li>
          <li>
            VeriFact reports, NewsFact reviews and feedback have no automatic deletion yet. We plan to add one; until then we delete
            them when you ask.
          </li>
          <li>Account details are kept until you delete your account.</li>
          <li>Logs are kept only as long as our hosting providers retain them, typically days to weeks.</li>
        </ul>
      </section>

      <section>
        <h2>Your choices and rights</h2>
        <ul>
          <li>
            <strong>Delete your account</strong> at any time from your <Link href="/account">account page</Link>. This removes your
            sign-in and profile straight away.
          </li>
          <li>
            <strong>Ask us</strong> to see, correct or delete data about you, including a specific report or review (send its link),
            by emailing <Mail />. We&apos;ll reply within 30 days.
          </li>
        </ul>
        <p>
          Depending on where you live, data protection laws (such as the Philippine Data Privacy Act of 2012 or the EU&apos;s GDPR)
          may give you further rights, including the right to complain to your data protection authority.
        </p>
      </section>

      <section>
        <h2>Security</h2>
        <p>
          Connections are encrypted, edit keys and passwords are stored only as hashes, and access to our database is restricted.
          No system is perfectly secure; if a breach affects you, we&apos;ll tell you and the authorities as the law requires.
        </p>
      </section>

      <section>
        <h2>Children and students</h2>
        <p>
          You must be at least 13 to use VeriFact. If you&apos;re under 18, get a parent or guardian&apos;s permission first. If you
          believe a child under 13 has given us personal information, email us and we&apos;ll delete it.
        </p>
      </section>

      <section>
        <h2>Changes</h2>
        <p>
          If we change this policy, we&apos;ll update the date above. For significant changes, we&apos;ll also say so on the site
          or email account holders.
        </p>
      </section>

      <p className="policy-footer muted small">
        See also our <Link href="/terms">Terms of Use</Link>.
      </p>
    </PolicyPage>
  );
}

export function TermsPage() {
  return (
    <PolicyPage
      id="terms"
      title="Terms of Use"
      lede="The rules for using VeriFact. By using the site or creating an account, you agree to them."
    >
      <section>
        <h2>What VeriFact is, and isn&apos;t</h2>
        <p>
          VeriFact helps you check claims against published sources. It uses AI and web search, and{" "}
          <strong>it can be wrong</strong>: it can miss sources, misread them, or rely on sources that are themselves wrong. Treat
          results as a starting point, read the linked sources, and use your own judgement.
        </p>
        <ul>
          <li>Results are not professional advice. LegalFact gives legal information, not legal advice; for your situation, ask a lawyer.</li>
          <li>For school work, follow your teacher&apos;s rules on AI tools and cite the original sources, not VeriFact.</li>
        </ul>
      </section>

      <section>
        <h2>Who can use it</h2>
        <p>
          You must be at least 13. If you&apos;re under 18, you need a parent or guardian&apos;s permission, and they accept these
          terms for you. Keep your password private; you&apos;re responsible for what happens under your account.
        </p>
      </section>

      <section>
        <h2>Using it fairly</h2>
        <p>Please don&apos;t:</p>
        <ul>
          <li>Submit content you have no right to share, or private information about other people.</li>
          <li>Use VeriFact to harass anyone, or to create or spread content you know is false.</li>
          <li>Present a VeriFact result as an official finding, or edit a report and pass it off as ours.</li>
          <li>
            Overload, scrape or automate the service, get around its limits, or try to break into it. If you find a security problem,
            tell us at <Mail />.
          </li>
          <li>Break the law.</li>
        </ul>
        <p>We may limit, suspend or remove access, and delete content, if these rules are broken.</p>
      </section>

      <section>
        <h2>Your content</h2>
        <p>
          You keep any rights you have in what you submit. You give us permission to process it, store it as described in our{" "}
          <Link href="/privacy">Privacy Policy</Link>, and show the results to you and to anyone you share the link with, only to run
          and improve VeriFact.
        </p>
      </section>

      <section>
        <h2>Our content</h2>
        <p>
          You may share and quote VeriFact reports, with a link back. The VeriFact name, logo and site design are ours. Sources linked
          in a report belong to their publishers.
        </p>
      </section>

      <section>
        <h2>Availability</h2>
        <p>
          VeriFact is free for now. We may change, limit or stop any part of it, and some features may later need an account or
          payment; we&apos;ll give notice before charging for anything.
        </p>
      </section>

      <section>
        <h2>No warranty; limited liability</h2>
        <p>
          VeriFact is provided &quot;as is&quot;, without warranties of accuracy, completeness or availability. As far as the law
          allows, we aren&apos;t liable for losses from relying on results or from the service being unavailable. Nothing here limits
          rights you have under laws that can&apos;t be waived.
        </p>
      </section>

      <section>
        <h2>Ending</h2>
        <p>
          You can stop using VeriFact and <Link href="/account">delete your account</Link> at any time. The sections on your content,
          no warranty and liability continue after that.
        </p>
      </section>

      <section>
        <h2>Changes and contact</h2>
        <p>
          We may update these terms and will change the date above when we do. If you keep using VeriFact afterwards, you accept the
          new terms. Questions: <Mail />.
        </p>
      </section>

      <p className="policy-footer muted small">
        See also our <Link href="/privacy">Privacy Policy</Link>.
      </p>
    </PolicyPage>
  );
}
