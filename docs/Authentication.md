# Authentication
Grayjay supports offering platform login for a plugin. This however comes with several security concerns that we attempt to alleviate partially.

The goal of the authentication system is to provide plugins the ability to make authenticated requests without directly exposing credentials and tokens to the plugin. This is done by keeping all this data on the app side, and never passing it to the plugin.

>:warning: **This is not bulletproof**  
>Depending on the platform, the plugin still has full access to making authenticated requests, including ones that may expose your account to danger (like changing settings). Or if a platform exposes values (insecurely) in the response data (not headers).
>
>You should always only login (and install for that matter) plugins you trust.

How to actually use the authenticated client is described in the Http package documentation  (See [Package: Http](_blank)).
This documentation will exclusively focus on configuring authentication and how it behaves.

## How it works
The authentication system works by allowing plugins to provide a login url, and a set of required headers/cookies/urls. When the user tries to log in, it will open the provided login url in an in-app webbrowser. Once all requirements are met, it will close this webbrowser and save the required data encrypted to app storage.

These authentication configs are put in the plugin config under the ```authentication``` property.
## Example
Here is an example of such an authentication configuration:

```json
	"authentication": {
		"loginUrl": "https://platform.com/some/login/url",
		"completionUrl": "https://platform.com/some/required/page", //Optional
		"loginButton": ".someContainer div .someButton" //Optional
		"userAgent": "Some User Agent", //Optional
		"domainHeadersToFind": { //Optional
			".platform.com": ["authorization"],
			"subdomain.platform.com": ["someHeader"],
			".somerelatedplatform.com": ["someOtherHeader"],
		},
		"cookiesToFind": ["someCookieToFind", "someOtherCookieToFind"], //Optional
		//"cookiesExclOthers": false //Optional
		//"allowedDomains": ["platform.com", "subdomain.platform.com"] //Optional
	}
```
Most platforms will only need a single header or cookie to function, but for some you may need very specific cookies for specific subdomains. 

| | Property | Usage |
|--|--|--|
| **Mandatory** | ```loginUrl``` | Used to set the initial url for the login browser. |
| Optional | ```completionUrl``` | Can be used to set a url that needs to be visited before concluding login. |
| Optional | ```loginButton``` | Can be used to trigger a html element by providing a query selector to a single html element. This button is then "clicked" after the page finishes loading. This supports full query selector including classes, ids, tags, and more advanced like :first-child. |
| Optional | ```userAgent``` | Can be used to set the user-agent of the browser during login. |
| Optional | ```domainHeadersToFind``` | Can be used to find headers for specific subdomains. |
| Optional | ```cookiesToFind``` |  Can be used to find specific cookies. |
| Optional | ```cookiesExclOthers``` |Can be used in the niche scenario where all other cookies should be disgarded when authenticated request are used. This is rather uncommon. |
| Optional | ```allowedDomains``` | Can be used to only fulfill the above requirements on the domains specified in this property, any other domains may be cancelled. (NOT USEFUL FOR MOST PLUGINS) |


## Header Behavior
Headers are exclusively applied to the domains they are retrieved from. A plugin CANNOT send a header to a domain that it is not related to. 

>:warning: **Plugins can elevate a header to a parent domain**  
>However a plugin can elevate a header to a parent domain. Meaning that if a header is retrieved in a request to ```somedomain.platform.com```, by defining the header for ```.platform.com``` it will be send to all requests of to any ```platform.com``` domain. This might be required for some platforms.

## Cookie Behavior
By default, when authentication requests are made, the authenticated client will behave similar to that of a normal browser. Meaning that if the server you are communicating with sets new cookies, the client will use those cookies instead. These new cookies are NOT saved to disk, meaning that whenever that plugin reloads the cookies will revert to those assigned at login.

This behavior can be modified by using custom http clients as described in the http package documentation.
 (See [Package: Http](_blank))

