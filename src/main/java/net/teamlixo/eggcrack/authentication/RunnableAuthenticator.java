package net.teamlixo.eggcrack.authentication;

import net.teamlixo.eggcrack.EggCrack;
import net.teamlixo.eggcrack.account.AuthenticatedAccount;
import net.teamlixo.eggcrack.credential.Credentials;
import net.teamlixo.eggcrack.list.AbstractExtendedList;
import net.teamlixo.eggcrack.account.Account;
import net.teamlixo.eggcrack.account.AccountListener;
import net.teamlixo.eggcrack.credential.Credential;
import net.teamlixo.eggcrack.session.Session;
import net.teamlixo.eggcrack.session.Tracker;

import java.net.Proxy;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.logging.Level;

/**
 * Helper class designed to provide asynchronous authentication functionality to EggCrack. Calls AuthenticationCallback
 * with completed or failed responses. Handles one account, but narrows down to the AuthenticationService class, which
 * should be both thread-safe and support many separate requesting accounts asynchronously.
 */
public class RunnableAuthenticator implements Runnable {
    private Session session;
    private AuthenticationService authenticationService;
    private Account account;
    private Iterator<Credential> credentialIterator;
    private Iterator<Proxy> proxyIterator;
    private AuthenticationCallback authenticationCallback;
    private Tracker tracker;

    public RunnableAuthenticator(Session session,
                                 AuthenticationService authenticationService,
                                 Account account,
                                 Tracker tracker,
                                 Iterator<Credential> credentialIterator,
                                 Iterator<Proxy> proxyIterator,
                                 AuthenticationCallback authenticationCallback) {
        this.session = session;
        this.authenticationService = authenticationService;
        this.account = account;
        this.credentialIterator = credentialIterator;
        this.proxyIterator = proxyIterator;
        this.authenticationCallback = authenticationCallback;
        this.tracker = tracker;
    }

    @Override
    public void run() {
        if (!session.isRunning()) return;

        account.setState(Account.State.STARTED);

        Thread.currentThread().setName("Authenticator-" + account.getUsername());

        AccountListener accountListener = account.getListener();
        if (accountListener != null)
            accountListener.onAccountStarted(account);

        Credential credential = account.getUncheckedPassword() != null ?
                Credentials.createPassword(account.getUncheckedPassword()) : null;
        if (credential != null && accountListener != null)
            accountListener.onAccountAttempting(account, credential);

        while (proxyIterator.hasNext() && session.isRunning()) {
            try {
                if (credential == null) {
                    if (accountListener != null && session.isRunning())
                        accountListener.onAccountTried(account, credential);

                    credential = credentialIterator.next();

                    if (accountListener != null && session.isRunning())
                        accountListener.onAccountAttempting(account, credential);


                }

                EggCrack.LOGGER.finest("[Account: " + account.getUsername() +
                        "] Sending authentication request [password=" + credential.toString() + "]...");

                try {
                    AuthenticatedAccount authenticatedAccount =
                            authenticationService.authenticate(account, credential, proxyIterator.next());
                    if (authenticatedAccount != null) {
                        authenticationCallback.onAuthenticationCompleted(authenticatedAccount);
                        tracker.setAttempts(tracker.getAttempts() + 1);
                        if (accountListener != null && session.isRunning())
                            accountListener.onAccountCompleted(account, credential);
                        return;
                    } else
                        throw new AuthenticationException(
                                AuthenticationException.AuthenticationFailure.INCORRECT_CREDENTIAL, "Incorrect credential"
                        );
                } catch (AuthenticationException exception) {
                    if (exception.getFailure().hasRequested()) {
                        tracker.setRequests(tracker.getRequests() + 1);

                        if (accountListener != null && session.isRunning())
                            accountListener.onAccountRequested(account);
                    }

                    if (exception.getFailure().getAction() == AuthenticationException.AuthenticationAction.STOP) {
                        tracker.setAttempts(tracker.getAttempts() + 1);

                        EggCrack.LOGGER.warning("Stopping session for " + account.getUsername() + ": "
                                + exception.getMessage() + " (" + exception.getDetails() + ")");
                        break;
                    } else if (exception.getFailure().getAction() == AuthenticationException.AuthenticationAction.NEXT_CREDENTIALS) {
                        account.setProgress(((AbstractExtendedList.LoopedIterator)credentialIterator).getProgress());

                        if (accountListener != null && session.isRunning())
                            accountListener.onAccountTried(account, credential);

                        tracker.setAttempts(tracker.getAttempts() + 1);

                        if (account.getUncheckedPassword() == null) {
                            credential = credentialIterator.next();
                            if (accountListener != null && session.isRunning())
                                accountListener.onAccountAttempting(account, credential);
                        } else
                            break; // Checker only tries one password.
                    }
                }
            } catch (NoSuchElementException exception) {
                exception.printStackTrace();
                break;
            } catch (Throwable ex) {
                //Just ignore it.
                EggCrack.LOGGER.log(Level.SEVERE, "Unhandled exception in thread " + Thread.currentThread().getName(), ex);
            }
        }

        if (accountListener != null && session.isRunning()) accountListener.onAccountFailed(account );
        authenticationCallback.onAuthenticationFailed(account);
        account.setState(Account.State.FINISHED);
    }
}
