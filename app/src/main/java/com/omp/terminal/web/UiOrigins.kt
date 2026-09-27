package com.omp.terminal.web

import android.content.Context
import com.omp.terminal.ChatService
import omp.shell.PlatformServices
import omp.shell.fs.Vfs
import omp.vm.provision.GuestWeb
import omp.vm.provision.ProvisionPaths
import omp.vm.provision.ProvisionState

/**
 * Where the chat is being served from, and the two things the app has to know to open it.
 *
 * **The port and the token are read from the files the service wrote, not from a field.** A service
 * that could not bind its preferred port takes an ephemeral one, so a port held in memory in a second
 * place is a port that can be wrong; and a token that was regenerated is a token whose old copy
 * would be a credential that no longer works. Both are facts on disk, and this is where the app
 * asks for them.
 */
object UiOrigins {

    /**
     * The port the guest's Apache answers on.
     *
     * **[omp.vm.provision.GuestWeb.RESERVED_PORT] and not a literal here.** Three things have to
     * agree about this number — this origin's base URL, the port [omp.vm.provision.GuestStart]
     * reserves and records, and the `origin:` and `guest origin:` lines `omp doctor` prints — and one
     * constant in `:core` is the only way they cannot drift. It is the same constant
     * [omp.vm.doctor.DoctorCommand] hands its report, and `omp.vm.provision.GuestWeb` is where the
     * reasons for it and for refusing a collision are written down.
     *
     * **A number is necessary and it is not sufficient**, and the difference is the whole of
     * [choose]: this constant says where the guest's Apache *would* answer, and only a page that came
     * back says it *is*. Naming the port was the half of the gap that made the guest unreachable;
     * the other half is [guestServing], and a build that only did the first would hand a WebView an
     * address that never loads on every device where the guest did not come up — and, where
     * something else held the number, a chat from a program the user was never told about.
     */
    const val GUEST_UI_PORT: Int = GuestWeb.RESERVED_PORT

    /**
     * The origin for this device right now, read from the platform.
     *
     * **A reader and not a decision.** Every fact is gathered here and handed to [choose], which is
     * the whole of the rule, so there is exactly one place where "which server is this" is answered
     * and it is a function of four values rather than of a `Context`.
     */
    fun forDevice(context: Context, services: PlatformServices, vfs: Vfs): UiOrigin? = choose(
        state = ProvisionPaths.inAppStorage(services).state(vfs),
        guestServing = com.omp.terminal.vm.GuestRuntime.serving,
        token = ChatService.publishedToken(context),
        loopbackBase = publishedBase(context),
    )

    /**
     * The same decision with every fact handed in, and no `Context` anywhere.
     *
     * **This is the whole of the rule, and it is four values rather than a `Context` so it can be
     * asked on a JVM.** A `Context` is needed for two things — the token and the published URL — and
     * both are strings this build wrote down itself. What is left is the interesting half: *a guest
     * is shown only when a page came back from it*, and a test that had to build a `Context` to ask
     * that question could not be written at all.
     *
     * - [state] is what is on the disk, and it is [UiOrigin.choose]'s half of the decision.
     * - [guestServing] is [omp.vm.provision.GuestState.serving] on the report
     *   [omp.vm.provision.GuestStart] left behind, and it is false until a request for this build's
     *   own chat document comes back from [GUEST_UI_PORT]. **A Debian that was downloaded is not a
     *   guest that is answering**, and a server on a port is not this build's guest either; the
     *   probe is what makes the second claim.
     * - [token] is the app's own credential, issued once by the service and appended to the URL the
     *   page exchanges for a cookie. It is never minted here: a second mint is a second token, and
     *   whichever one a page were handed would be the one that worked while the other silently did
     *   not.
     * - [loopbackBase] is the app's own published address with the credential stripped.
     *
     * **Null in, null out, and never a substituted value.** A missing token or a missing published
     * base gives null rather than a base URL with a blank in it, because
     * [HttpLoopback.Base.parse] refuses the second and the refusal would arrive as a crash on a
     * phone instead of as "there is nothing to show yet".
     */
    fun choose(
        state: ProvisionState,
        guestServing: Boolean,
        token: String?,
        loopbackBase: String?,
    ): UiOrigin? {
        if (loopbackBase == null || token == null) return null
        val loopback = LoopbackOrigin(loopbackBase, token)
        val guest = if (guestServing) GuestOrigin(GuestWeb.baseUrl(GUEST_UI_PORT), token) else null
        return UiOrigin.choose(state, guest, loopback)
    }

    /** `http://127.0.0.1:PORT`, no trailing slash and no path — the shape [HttpLoopback] validates. */
    fun baseUrl(port: Int): String = GuestWeb.baseUrl(port)

    /**
     * The host and port the service published, or null. The token is stripped from it.
     *
     * **Cut at the first `/`, not at `?t=`.** The file holds `http://host:port/login?t=token`, and
     * taking everything after the scheme would carry the credential into a base URL that is then
     * validated — so a mistake here would be a mistake the validation cannot see.
     */
    private fun publishedBase(context: Context): String? {
        val url = ChatService.publishedUrl(context) ?: return null
        return url.substringAfter("://", "").substringBefore('/').takeIf { it.isNotEmpty() }
    }
}
