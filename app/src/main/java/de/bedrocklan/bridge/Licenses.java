package de.bedrocklan.bridge;

/**
 * Lizenz- und Namensnennungstexte der gebündelten Fremdsoftware,
 * in der App über „Lizenzen“ einsehbar.
 */
final class Licenses {
    private Licenses() {}

    static String text() {
        StringBuilder b = new StringBuilder();
        b.append("Die Bedrock LAN-Brücke bündelt und/oder leitet Code ab von den folgenden Projekten.\n\n");

        b.append("— Gebündelte Abhängigkeit —\n\n");
        b.append("stream-webrtc-android (GetStream)\n");
        b.append("Apache License 2.0 — Copyright GetStream. Das zugrunde liegende Google-libwebrtc ");
        b.append("(Copyright The WebRTC Authors) ist zusätzlich unter BSD-3-Clause lizenziert. ");
        b.append("Der vollständige Apache-2.0-Text steht unten.\n\n");

        b.append("— Protokoll-Referenzen (Java-Portierungen/Ableitungen) —\n\n");

        b.append("go-nethernet (df-mc) — MIT License\n");
        b.append("Copyright (c) 2019 Dragonfly Tech\n");
        b.append("Grundlage für NetherNet-LAN-Discovery, Signaling, Segment-Framing und Identitäts-Assertionen.\n\n");

        b.append("gophertunnel (Sandertv) — MIT License\n");
        b.append("Copyright (c) 2019 Sandertv\n");
        b.append("Grundlage für die Xbox-Online-Anmeldung (MSA → XSTS → PlayFab → MCToken).\n\n");

        b.append("Geyser (GeyserMC) — MIT License\n");
        b.append("Copyright (c) 2019-2026 GeyserMC. http://geysermc.org\n");
        b.append("Grundlage für Transport-Identity-Binding und Login-Validierung.\n\n");

        b.append("CloudburstMC Network & Protocol (CloudburstMC) — Apache License 2.0\n");
        b.append("Copyright 2022 CloudburstMC\n");
        b.append("Grundlage für HTTP-Signaling (/v1/join), Identity-Validierung und Login-Envelope-Format.\n\n");

        b.append("acacia (acacia-rs) — MIT License\n");
        b.append("Copyright (c) 2026 Hexay\n");
        b.append("Grundlage (docs/auth.md) für Endpunkte und Ablauf der Authentifizierungskette.\n\n");

        b.append("nethernet-spec (df-mc) — keine Lizenzdatei (Protokollbeschreibung)\n");
        b.append("Grundlage der LAN-Discovery-Implementierung; keine Text- oder Codeübernahme.\n\n");

        b.append("— MIT License (Wortlaut) —\n\n");
        b.append("Permission is hereby granted, free of charge, to any person obtaining a copy\n");
        b.append("of this software and associated documentation files (the \"Software\"), to deal\n");
        b.append("in the Software without restriction, including without limitation the rights\n");
        b.append("to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n");
        b.append("copies of the Software, and to permit persons to whom the Software is\n");
        b.append("furnished to do so, subject to the following conditions:\n\n");
        b.append("The above copyright notice and this permission notice shall be included in all\n");
        b.append("copies or substantial portions of the Software.\n\n");
        b.append("THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n");
        b.append("IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n");
        b.append("FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n");
        b.append("AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n");
        b.append("LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n");
        b.append("OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n");
        b.append("SOFTWARE.\n\n");

        b.append("— Apache License 2.0 (Wortlaut, gekürzt sichtbar; verbindlich: apache.org/licenses/LICENSE-2.0) —\n\n");
        b.append(APACHE2);
        b.append("\n\nVollständige Lizenztexte auch unter:\n");
        b.append("https://github.com/x1708er/bedrock-bridge/blob/main/THIRD_PARTY_LICENSES.md");
        return b.toString();
    }

    private static final String APACHE2 = ""
            + "Apache License\n"
            + "Version 2.0, January 2004\n"
            + "http://www.apache.org/licenses/\n\n"
            + "1. Definitions. \"License\" shall mean the terms and conditions for use,\n"
            + "reproduction, and distribution as defined by Sections 1 through 9 of this\n"
            + "document. \"Licensor\" shall mean the copyright owner or entity authorized by\n"
            + "the copyright owner that is granting the License. \"Legal Entity\" shall mean\n"
            + "the union of the acting entity and all other entities that control, are\n"
            + "controlled by, or are under common control with that entity. \"You\" (or\n"
            + "\"Your\") shall mean an individual or Legal Entity exercising permissions\n"
            + "granted by this License. \"Source\" form shall mean the preferred form for\n"
            + "making modifications. \"Object\" form shall mean any form resulting from\n"
            + "mechanical transformation or translation of a Source form. \"Work\" shall mean\n"
            + "the work of authorship made available under the License. \"Derivative Works\"\n"
            + "shall mean any work based on the Work. \"Contribution\" shall mean any work of\n"
            + "authorship submitted to Licensor for inclusion in the Work. \"Contributor\"\n"
            + "shall mean Licensor and any individual or Legal Entity on behalf of whom a\n"
            + "Contribution has been received.\n\n"
            + "2. Grant of Copyright License. Subject to the terms and conditions of this\n"
            + "License, each Contributor hereby grants to You a perpetual, worldwide,\n"
            + "non-exclusive, no-charge, royalty-free, irrevocable copyright license to\n"
            + "reproduce, prepare Derivative Works of, publicly display, publicly perform,\n"
            + "sublicense, and distribute the Work and such Derivative Works in Source or\n"
            + "Object form.\n\n"
            + "3. Grant of Patent License. Subject to the terms and conditions of this\n"
            + "License, each Contributor hereby grants to You a perpetual, worldwide,\n"
            + "non-exclusive, no-charge, royalty-free, irrevocable patent license to make,\n"
            + "have made, use, offer to sell, sell, import, and otherwise transfer the Work.\n"
            + "If You institute patent litigation alleging that the Work constitutes direct\n"
            + "or contributory patent infringement, then any patent licenses granted to You\n"
            + "under this License for that Work shall terminate as of the date such\n"
            + "litigation is filed.\n\n"
            + "4. Redistribution. You may reproduce and distribute copies of the Work or\n"
            + "Derivative Works thereof in any medium, with or without modifications, and\n"
            + "in Source or Object form, provided that You meet the following conditions:\n"
            + "(a) You must give any other recipients of the Work or Derivative Works a\n"
            + "copy of this License; (b) You must cause any modified files to carry\n"
            + "prominent notices stating that You changed the files; (c) You must retain,\n"
            + "in the Source form of any Derivative Works that You distribute, all\n"
            + "copyright, patent, trademark, and attribution notices from the Source form\n"
            + "of the Work; (d) If the Work includes a \"NOTICE\" text file, any Derivative\n"
            + "Works You distribute must include a readable copy of the attribution notices\n"
            + "contained within such NOTICE file.\n\n"
            + "5. Submission of Contributions. Unless You explicitly state otherwise, any\n"
            + "Contribution intentionally submitted for inclusion in the Work by You to\n"
            + "the Licensor shall be under the terms and conditions of this License.\n\n"
            + "6. Trademarks. This License does not grant permission to use the trade\n"
            + "names, trademarks, service marks, or product names of the Licensor, except\n"
            + "as required for reasonable and customary use in describing the origin of the\n"
            + "Work.\n\n"
            + "7. Disclaimer of Warranty. Unless required by applicable law or agreed to\n"
            + "in writing, Licensor provides the Work on an \"AS IS\" BASIS, WITHOUT\n"
            + "WARRANTIES OR CONDITIONS OF ANY KIND.\n\n"
            + "8. Limitation of Liability. In no event and under no legal theory, unless\n"
            + "required by applicable law or agreed to in writing, shall any Contributor\n"
            + "be liable to You for damages of any character arising as a result of this\n"
            + "License or out of the use or inability to use the Work.\n\n"
            + "9. Accepting Warranty or Additional Liability. While redistributing the\n"
            + "Work, You may choose to offer support, warranty, indemnity, or other\n"
            + "liability obligations consistent with this License.\n\n"
            + "END OF TERMS AND CONDITIONS";
}
