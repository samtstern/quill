package me.vickychijwani.spectre.view;

import android.support.annotation.IdRes;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.app.NavUtils;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.MenuItem;

import java.util.List;

import me.vickychijwani.spectre.model.AuthToken;
import me.vickychijwani.spectre.view.fragments.BaseFragment;

public abstract class BaseActivity extends ActionBarActivity {

    // TODO get rid of this shit
    protected static AuthToken sAuthToken = null;

    public static AuthToken getAuthToken() {
        return sAuthToken;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                NavUtils.navigateUpFromSameTask(this);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onBackPressed() {
        List<Fragment> fragments = getSupportFragmentManager().getFragments();
        if (fragments != null) {
            for (Fragment f : fragments) {
                if (!(f instanceof BaseFragment)) {
                    continue;  // vanilla fragments don't have onBackPressed
                }

                BaseFragment bf = (BaseFragment) f;
                if (bf.onBackPressed()) {
                    return;
                }
            }
        }
        super.onBackPressed();
    }

    /**
     * Add a {@link android.support.v4.app.Fragment} of the given type {@link T}. NOTE: this method
     * is only designed to work with Fragments that have a zero-argument static factory method named
     * newInstance().
     *
     * @param type      the class of the Fragment to add
     * @param container the ID of the view container in which to add this Fragment's view root
     * @param tag       the tag to assign to this Fragment, used for retrieval later
     * @param <T>       the type of the Fragment
     * @return a non-null instance of type {@link T} (could be a new instance or one already
     *                  attached to the activity)
     */
    @SuppressWarnings("unchecked")
    @NonNull
    public <T extends Fragment> T addFragment(Class<T> type, @IdRes int container, String tag) {
        T fragment = (T) getSupportFragmentManager().findFragmentByTag(tag);
        if (fragment == null) {
            try {
                fragment = type.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                Log.wtf("WTF", "Given Fragment class does not have a zero-argument static factory" +
                        "method named newInstance(), as required by this method");
            }
            getSupportFragmentManager()
                    .beginTransaction()
                    .add(container, fragment, tag)
                    .commit();
        }
        return fragment;
    }

}
